package com.ninezero.features.user.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.BlockOperationFailedException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.CursorResponse
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toCursorPage
import com.ninezero.core.common.util.validateCursorLimit
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.response.BlockRelationResponse
import com.ninezero.features.user.presentation.models.response.BlockToggleResponse
import com.ninezero.features.user.presentation.models.response.BlockedUserListResponse
import com.ninezero.features.user.toUserSummaryResponse

class BlockedUserService(
    private val blockedUserRepository: BlockedUserRepository,
    private val userRepository: UserRepository,
    private val followRepository: FollowRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val cacheService: CacheService
) {

    suspend fun toggleBlock(userId: Int, targetUserId: Int): BlockToggleResponse {
        if (userId == targetUserId) {
            throw InvalidInputException(Errors.User.Block.CANNOT_BLOCK_SELF)
        }

        val response = query {
            userRepository.findUserById(targetUserId)
                ?: throw UserNotFoundException(targetUserId)

            val isCurrentlyBlocked = blockedUserRepository.isBlocked(userId, targetUserId)

            if (isCurrentlyBlocked) {
                val deleted = blockedUserRepository.deleteBlock(userId, targetUserId)
                if (!deleted) {
                    throw BlockOperationFailedException(Errors.User.Block.UNBLOCK_FAILED)
                }

                BlockToggleResponse(
                    isBlocked = false,
                    message = Messages.Social.UNBLOCK_SUCCESS
                )
            } else {
                blockedUserRepository.createBlock(userId, targetUserId)
                    ?: throw BlockOperationFailedException(Errors.User.Block.BLOCK_FAILED)

                unfollowEachOther(userId, targetUserId)  // 차단 시 맞팔로우 해제

                // 차단 관계의 구독은 양방향 모두 자동갱신 해제
                subscriptionRepository.findActiveSubscription(userId, targetUserId)?.let {
                    it.autoRenew = false
                }
                subscriptionRepository.findActiveSubscription(targetUserId, userId)?.let {
                    it.autoRenew = false
                }

                BlockToggleResponse(
                    isBlocked = true,
                    message = Messages.Social.BLOCK_SUCCESS
                )
            }
        }

        // 캐시 적중이 차단 검사보다 먼저라 양쪽 뷰어 캐시를 비움
        cacheService.deletePattern(CacheKeys.Patterns.userScoped(userId))
        cacheService.deletePattern(CacheKeys.Patterns.userScoped(targetUserId))

        return response
    }

    /** 양방향 차단 관계 */
    suspend fun getBlockRelation(userId: Int, targetUserId: Int): BlockRelationResponse {
        return query {
            val isBlockedByMe = blockedUserRepository.isBlocked(userId, targetUserId)
            val isBlockedByOther = blockedUserRepository.isBlocked(targetUserId, userId)
            BlockRelationResponse(
                isBlockedByMe = isBlockedByMe,
                isBlockedByOther = isBlockedByOther,
                isBlocked = isBlockedByMe || isBlockedByOther
            )
        }
    }

    suspend fun getBlockedUsers(
        userId: Int,
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): BlockedUserListResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            val (blocks, nextCursor) = blockedUserRepository.findBlockedUsers(userId, pageCursor, validLimit + 1)
                .toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }

            val userMap = userRepository.findUsersByIds(blocks.map { it.blockedUserId }).associateBy { it.id.value }

            val userSummaries = blocks.mapNotNull { block ->
                userMap[block.blockedUserId]?.toUserSummaryResponse()
            }

            CursorResponse(userSummaries, nextCursor, hasNext = nextCursor != null)
        }
    }

    private suspend fun unfollowEachOther(userId: Int, targetUserId: Int) {
        followRepository.deleteFollow(userId, targetUserId)
        followRepository.deleteFollow(targetUserId, userId)
    }
}
