package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InternalServerException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.UserBlockedException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.PaginatedResponse
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toCursorPage
import com.ninezero.core.common.util.validateCursorLimit
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.presentation.models.response.FollowListResponse
import com.ninezero.features.social.presentation.models.response.FollowResponse
import com.ninezero.features.social.presentation.models.response.FollowedCreatorResponse
import com.ninezero.features.social.presentation.models.response.MutualFollowResponse
import com.ninezero.features.social.presentation.models.response.NotificationSettingResponse
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import com.ninezero.features.user.toUserSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class FollowService(
    private val followRepository: FollowRepository,
    private val userRepository: UserRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val notificationService: NotificationService,
    private val coroutineScope: CoroutineScope,
    private val cacheService: CacheService
) {
    private val logger = logger()

    // 팔로우/언팔로우
    suspend fun followUser(followerId: Int, followingId: Int): FollowResponse {
        if (followerId == followingId) {
            throw InvalidInputException(Errors.Social.Follow.CANNOT_FOLLOW_SELF)
        }

        val response = query {
            if (blockedUserRepository.isBlockedEither(followerId, followingId)) {
                throw UserBlockedException(Errors.User.Block.USER_BLOCKED)
            }

            userRepository.findUserById(followingId)
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            if (followRepository.isFollowing(followerId, followingId)) {
                throw ConflictException(Errors.Social.Follow.ALREADY_FOLLOWING)
            }

            followRepository.createFollow(followerId, followingId)
                ?: throw InternalServerException(Errors.Social.Follow.FOLLOW_FAILED)

            FollowResponse(
                followerCount = followRepository.countFollowers(followingId),
                followingCount = followRepository.countFollowing(followerId),
                userId = followingId
            )
        }

        // follower 개인화 캐시 무효화
        cacheService.deletePattern(CacheKeys.Patterns.userScoped(followerId))
        // 양쪽 프로필의 팔로워·팔로잉 수
        cacheService.delete(CacheKeys.profile(followerId))
        cacheService.delete(CacheKeys.profile(followingId))

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendFollowNotification(followerId, followingId)
            } catch (e: Exception) {
                logger.error("팔로우 알림 전송 실패: followerId=$followerId, followingId=$followingId, error=${e.message}", e)
            }
        }

        return response
    }

    suspend fun unfollowUser(followerId: Int, followingId: Int): FollowResponse {
        val response = query {
            if (!followRepository.isFollowing(followerId, followingId)) {
                throw ConflictException(Errors.Social.Follow.NOT_FOLLOWING)
            }

            val deleted = followRepository.deleteFollow(followerId, followingId)
            if (!deleted) {
                throw InternalServerException(Errors.Social.Follow.UNFOLLOW_FAILED)
            }

            FollowResponse(
                followerCount = followRepository.countFollowers(followingId),
                followingCount = followRepository.countFollowing(followerId),
                userId = followingId
            )
        }

        // follower 개인화 캐시 무효화
        cacheService.deletePattern(CacheKeys.Patterns.userScoped(followerId))
        // 양쪽 프로필의 팔로워·팔로잉 수
        cacheService.delete(CacheKeys.profile(followerId))
        cacheService.delete(CacheKeys.profile(followingId))

        return response
    }

    // 팔로워·팔로잉 목록
    suspend fun getFollowers(
        userId: Int,
        currentUserId: Int? = null,
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        search: String? = null
    ): FollowListResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            val (follows, nextCursor) = if (search.isNullOrBlank()) {
                followRepository.findFollowers(userId, pageCursor, validLimit + 1)
            } else {
                followRepository.findFollowersWithSearch(userId, search, pageCursor, validLimit + 1)
            }.toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }
            val totalCount = if (search.isNullOrBlank()) {
                followRepository.countFollowers(userId)
            } else {
                followRepository.countFollowersWithSearch(userId, search)
            }
            val userIds = follows.map { it.followerId }.distinct()

            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val followStatusMap = currentUserId?.let {
                followRepository.checkMultipleFollowStatus(it, userIds)
            } ?: emptyMap()

            val userSummaries = userIds.mapNotNull { id ->
                userMap[id]?.toUserSummaryResponse(
                    isFollowing = followStatusMap[id]
                )
            }

            FollowListResponse(userSummaries, nextCursor, hasNext = nextCursor != null, totalCount = totalCount)
        }
    }

    suspend fun getFollowing(
        userId: Int,
        currentUserId: Int? = null,
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        search: String? = null
    ): FollowListResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            val (follows, nextCursor) = if (search.isNullOrBlank()) {
                followRepository.findFollowing(userId, pageCursor, validLimit + 1)
            } else {
                followRepository.findFollowingWithSearch(userId, search, pageCursor, validLimit + 1)
            }.toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }
            val totalCount = if (search.isNullOrBlank()) {
                followRepository.countFollowing(userId)
            } else {
                followRepository.countFollowingWithSearch(userId, search)
            }
            val userIds = follows.map { it.followingId }.distinct()

            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val followStatusMap = currentUserId?.let {
                followRepository.checkMultipleFollowStatus(it, userIds)
            } ?: emptyMap()

            val userSummaries = userIds.mapNotNull { id ->
                userMap[id]?.toUserSummaryResponse(
                    isFollowing = followStatusMap[id]
                )
            }

            FollowListResponse(userSummaries, nextCursor, hasNext = nextCursor != null, totalCount = totalCount)
        }
    }

    /** 팔로우 크리에이터 목록과 알림 설정 */
    suspend fun getFollowedCreators(
        userId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): PaginatedResponse<FollowedCreatorResponse> {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val follows = followRepository.findFollowedCreators(userId, validPage, validLimit)  // 알림 대상=크리에이터만
            val totalCount = followRepository.countFollowedCreators(userId)

            val userMap = userRepository.findUsersByIds(follows.map { it.followingId })
                .associateBy { it.id.value }

            val items = follows.mapNotNull { follow ->
                val user = userMap[follow.followingId] ?: return@mapNotNull null
                FollowedCreatorResponse(
                    id = user.id.value,
                    username = user.username,
                    displayName = user.profile?.displayName ?: user.username,
                    avatarUrl = user.profile?.avatarUrl,
                    avatarThumbUrl = user.profile?.avatarThumbUrl,
                    notifyNewPost = follow.notifyNewPost,
                    notifyNewProduct = follow.notifyNewProduct
                )
            }

            createPagedResponse(items, PaginationInfo(validPage, validLimit, totalCount))
        }
    }

    // 맞팔·추천
    suspend fun getMutualFollow(userId1: Int, userId2: Int): MutualFollowResponse {
        return query {
            val aFollowsB = followRepository.isFollowing(userId1, userId2)
            val bFollowsA = followRepository.isFollowing(userId2, userId1)
            MutualFollowResponse(aFollowsB = aFollowsB, bFollowsA = bFollowsA, isMutualFollow = aFollowsB && bFollowsA)
        }
    }

    suspend fun getFollowRecommendations(userId: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<UserSummaryResponse> {
        return query {
            val userFollowing = followRepository.findFollowing(userId, cursor = null, limit = Constants.Social.FOLLOW_RECOMMENDATION_SOURCE_LIMIT)
            val followingIds = userFollowing.map { it.followingId }

            if (followingIds.isEmpty()) {
                return@query emptyList()
            }

            val theirFollowingsMap = followRepository.findFollowingIdsByUserIds(followingIds, Constants.Social.FOLLOW_RECOMMENDATION_PER_SOURCE_LIMIT)
            val allTheirFollowingIds = theirFollowingsMap.values.flatten()
            val blockedUserIds = blockedUserRepository.findBlockRelatedUserIds(userId)  // 차단 관계는 추천 제외

            val followingIdSet = followingIds.toSet()

            val candidateUserIds = allTheirFollowingIds
                .distinct()
                .filter { it != userId && it !in followingIdSet && it !in blockedUserIds }

            if (candidateUserIds.isEmpty()) {
                return@query emptyList()
            }

            val followStatusMap = followRepository.checkMultipleFollowStatus(userId, candidateUserIds)

            val recommendedUserIds = candidateUserIds
                .filter { followStatusMap[it] == false }
                .take(limit)

            // followerCount·isFollowing 기본값
            val users = userRepository.findUsersByIds(recommendedUserIds)
            val followerCounts = followRepository.countFollowersByUserIds(recommendedUserIds)
            users.map { user ->
                user.toUserSummaryResponse(
                    followerCount = followerCounts[user.id.value] ?: 0,
                    isFollowing = false
                )
            }
        }
    }

    // 크리에이터 알림 설정
    suspend fun updateNewPostNotification(
        followerId: Int,
        followingId: Int,
        enabled: Boolean
    ): NotificationSettingResponse {
        return query {
            if (!followRepository.isFollowing(followerId, followingId)) {
                throw ConflictException(Errors.Social.Follow.NOT_FOLLOWING)
            }

            val success = followRepository.updateNewPostNotification(followerId, followingId, enabled)
            if (!success) {
                throw InternalServerException(Errors.Social.Follow.NOTIFICATION_UPDATE_FAILED)
            }

            NotificationSettingResponse(notifyNewPost = enabled)
        }
    }

    suspend fun updateNewProductNotification(
        followerId: Int,
        followingId: Int,
        enabled: Boolean
    ): NotificationSettingResponse {
        return query {
            if (!followRepository.isFollowing(followerId, followingId)) {
                throw ConflictException(Errors.Social.Follow.NOT_FOLLOWING)
            }

            val success = followRepository.updateNewProductNotification(followerId, followingId, enabled)
            if (!success) {
                throw InternalServerException(Errors.Social.Follow.NOTIFICATION_UPDATE_FAILED)
            }

            NotificationSettingResponse(notifyNewProduct = enabled)
        }
    }
}
