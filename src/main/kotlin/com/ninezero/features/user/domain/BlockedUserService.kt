package com.ninezero.features.user.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.BlockOperationFailedException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.response.BlockToggleResponse
import com.ninezero.features.user.presentation.models.response.BlockedUserListResponse
import com.ninezero.features.user.toSummaryResponse

class BlockedUserService(
    private val blockedUserRepository: BlockedUserRepository,
    private val userRepository: UserRepository,
    private val followRepository: FollowRepository,
    private val subscriptionRepository: SubscriptionRepository
) {

    suspend fun toggleBlock(userId: Int, targetUserId: Int): BlockToggleResponse {
        if (userId == targetUserId) {
            throw IllegalArgumentException(Errors.User.Block.CANNOT_BLOCK_SELF)
        }

        return query {
            userRepository.findUserById(targetUserId)
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

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

                // 구독 중인 크리에이터 차단 시 자동갱신 해제
                subscriptionRepository.findActiveSubscription(userId, targetUserId)?.let {
                    it.autoRenew = false
                }

                BlockToggleResponse(
                    isBlocked = true,
                    message = Messages.Social.BLOCK_SUCCESS
                )
            }
        }
    }

    private suspend fun unfollowEachOther(userId: Int, targetUserId: Int) {
        if (followRepository.isFollowing(userId, targetUserId)) {
            followRepository.deleteFollow(userId, targetUserId)
        }

        if (followRepository.isFollowing(targetUserId, userId)) {
            followRepository.deleteFollow(targetUserId, userId)
        }
    }

    /** 단방향 차단 확인 */
    suspend fun isBlocked(userId: Int, targetUserId: Int): Map<String, Boolean> {
        return query {
            val isBlocked = blockedUserRepository.isBlocked(userId, targetUserId)
            mapOf("isBlocked" to isBlocked)
        }
    }

    /** 양방향 차단 관계 */
    suspend fun getBlockRelation(userId: Int, targetUserId: Int): Map<String, Boolean> {
        return query {
            val isBlockedByMe = blockedUserRepository.isBlocked(userId, targetUserId)
            val isBlockedByOther = blockedUserRepository.isBlocked(targetUserId, userId)
            mapOf(
                "isBlockedByMe" to isBlockedByMe,
                "isBlockedByOther" to isBlockedByOther,
                "isBlocked" to (isBlockedByMe || isBlockedByOther)
            )
        }
    }

    suspend fun getBlockedUsers(
        userId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): BlockedUserListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val blockedUserIds = blockedUserRepository.findBlockedUserIds(userId, validPage, validLimit)
            val totalCount = blockedUserRepository.countBlockedUsers(userId)

            val users = userRepository.findUsersByIds(blockedUserIds)
            val userMap = users.associateBy { it.id.value }

            val userSummaries = blockedUserIds.mapNotNull { blockedUserId ->
                userMap[blockedUserId]?.toSummaryResponse()
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)

            createPagedResponse(userSummaries, pagination)
        }
    }

    suspend fun getBlockedUserCount(userId: Int): Map<String, Int> {
        return query {
            val count = blockedUserRepository.countBlockedUsers(userId)
            mapOf("count" to count)
        }
    }
}
