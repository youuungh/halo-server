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
import com.ninezero.core.common.util.PaginatedResponse
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.presentation.models.response.FollowListResponse
import com.ninezero.features.social.presentation.models.response.FollowResponse
import com.ninezero.features.social.presentation.models.response.FollowedCreatorResponse
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import com.ninezero.features.user.toSummaryResponse
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

        // 알림 전송
        coroutineScope.launch {
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

        return response
    }

    // 팔로워·팔로잉 목록
    suspend fun getFollowers(
        userId: Int,
        currentUserId: Int? = null,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        search: String? = null
    ): FollowListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val (userIds, totalCount) = if (search.isNullOrBlank()) {
                val follows = followRepository.findFollowers(userId, validPage, validLimit)
                val count = followRepository.countFollowers(userId)
                follows.map { it.followerId }.distinct() to count
            } else {
                val ids = followRepository.findFollowersWithSearch(userId, search, validPage, validLimit)
                val count = followRepository.countFollowersWithSearch(userId, search)
                ids to count
            }

            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val followStatusMap = currentUserId?.let {
                followRepository.checkMultipleFollowStatus(it, userIds)
            } ?: emptyMap()

            val userSummaries = userIds.mapNotNull { id ->
                userMap[id]?.toSummaryResponse(
                    isFollowing = followStatusMap[id]
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(userSummaries, pagination)
        }
    }

    suspend fun getFollowing(
        userId: Int,
        currentUserId: Int? = null,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        search: String? = null
    ): FollowListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val (userIds, totalCount) = if (search.isNullOrBlank()) {
                val follows = followRepository.findFollowing(userId, validPage, validLimit)
                val count = followRepository.countFollowing(userId)
                follows.map { it.followingId }.distinct() to count
            } else {
                val ids = followRepository.findFollowingWithSearch(userId, search, validPage, validLimit)
                val count = followRepository.countFollowingWithSearch(userId, search)
                ids to count
            }

            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val followStatusMap = currentUserId?.let {
                followRepository.checkMultipleFollowStatus(it, userIds)
            } ?: emptyMap()

            val userSummaries = userIds.mapNotNull { id ->
                userMap[id]?.toSummaryResponse(
                    isFollowing = followStatusMap[id]
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(userSummaries, pagination)
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

    // 통계·맞팔·추천
    suspend fun getFollowStats(userId: Int): FollowResponse {
        return query {
            FollowResponse(
                followerCount = followRepository.countFollowers(userId),
                followingCount = followRepository.countFollowing(userId),
                userId = userId
            )
        }
    }

    suspend fun getMutualFollow(userId1: Int, userId2: Int): Map<String, Boolean> {
        return query {
            val user1FollowsUser2 = followRepository.isFollowing(userId1, userId2)
            val user2FollowsUser1 = followRepository.isFollowing(userId2, userId1)

            mapOf(
                "aFollowsB" to user1FollowsUser2,
                "bFollowsA" to user2FollowsUser1,
                "isMutualFollow" to (user1FollowsUser2 && user2FollowsUser1)
            )
        }
    }

    suspend fun getFollowRecommendations(userId: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<UserSummaryResponse> {
        return query {
            val userFollowing = followRepository.findFollowing(userId, 1, 100)
            val followingIds = userFollowing.map { it.followingId }

            if (followingIds.isEmpty()) {
                return@query emptyList()
            }

            val theirFollowingsMap = followRepository.findFollowingIdsByUserIds(followingIds, 20)
            val allTheirFollowingIds = theirFollowingsMap.values.flatten()

            val candidateUserIds = allTheirFollowingIds
                .distinct()
                .filter { it != userId && it !in followingIds }

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
                user.toSummaryResponse(
                    followerCount = followerCounts[user.id.value] ?: 0,
                    isFollowing = false
                )
            }
        }
    }

    // 팔로우 토글
    suspend fun toggleFollow(followerId: Int, followingId: Int): FollowResponse {
        if (followerId == followingId) {
            throw InvalidInputException(Errors.Social.Follow.CANNOT_FOLLOW_SELF)
        }

        val (response, isNewFollow) = query {
            val isFollowing = followRepository.isFollowing(followerId, followingId)

            if (isFollowing) {
                val deleted = followRepository.deleteFollow(followerId, followingId)
                if (!deleted) {
                    throw InternalServerException(Errors.Social.Follow.UNFOLLOW_FAILED)
                }
            } else {
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
            }

            val response = FollowResponse(
                followerCount = followRepository.countFollowers(followingId),
                followingCount = followRepository.countFollowing(followerId),
                userId = followingId
            )

            response to !isFollowing  // 팔로우 시에만 true
        }

        // 팔로우 시에만 알림 전송
        if (isNewFollow) {
            coroutineScope.launch {
                try {
                    notificationService.sendFollowNotification(followerId, followingId)
                } catch (e: Exception) {
                    logger.error("팔로우 알림 전송 실패: followerId=$followerId, followingId=$followingId, error=${e.message}", e)
                }
            }
        }

        return response
    }

    // 팔로우 상태·수
    suspend fun getUserFollowSummary(targetUserId: Int, currentUserId: Int? = null): Map<String, Any> {
        return query {
            val followerCount = followRepository.countFollowers(targetUserId)
            val followingCount = followRepository.countFollowing(targetUserId)

            val isFollowedByCurrentUser = currentUserId?.let {
                followRepository.isFollowing(it, targetUserId)
            } ?: false

            val isFollowingCurrentUser = currentUserId?.let {
                followRepository.isFollowing(targetUserId, it)
            } ?: false

            mapOf(
                "followerCount" to followerCount,
                "followingCount" to followingCount,
                "isFollowedBy" to isFollowedByCurrentUser,
                "isFollowing" to isFollowingCurrentUser,
                "isMutualFollow" to (isFollowedByCurrentUser && isFollowingCurrentUser)
            )
        }
    }

    suspend fun checkMultipleFollowStatus(currentUserId: Int, targetUserIds: List<Int>): Map<Int, Boolean> {
        return query {
            followRepository.checkMultipleFollowStatus(currentUserId, targetUserIds)
        }
    }

    suspend fun getFollowerCount(userId: Int): Map<String, Int> {
        return query {
            val count = followRepository.countFollowers(userId)
            mapOf("count" to count)
        }
    }

    suspend fun getFollowingCount(userId: Int): Map<String, Int> {
        return query {
            val count = followRepository.countFollowing(userId)
            mapOf("count" to count)
        }
    }

    suspend fun getFollowStatus(followerId: Int, followingId: Int): Map<String, Boolean> {
        return query {
            val isFollowing = followRepository.isFollowing(followerId, followingId)
            mapOf("isFollowing" to isFollowing)
        }
    }

    // 크리에이터 알림 설정
    suspend fun toggleNewPostNotification(
        followerId: Int,
        followingId: Int,
        enabled: Boolean
    ): Map<String, Boolean> {
        return query {
            if (!followRepository.isFollowing(followerId, followingId)) {
                throw ConflictException(Errors.Social.Follow.NOT_FOLLOWING)
            }

            val success = followRepository.updateNewPostNotification(followerId, followingId, enabled)
            if (!success) {
                throw InternalServerException(Errors.Social.Follow.NOTIFICATION_UPDATE_FAILED)
            }

            mapOf("notifyNewPost" to enabled)
        }
    }

    suspend fun toggleNewProductNotification(
        followerId: Int,
        followingId: Int,
        enabled: Boolean
    ): Map<String, Boolean> {
        return query {
            if (!followRepository.isFollowing(followerId, followingId)) {
                throw ConflictException(Errors.Social.Follow.NOT_FOLLOWING)
            }

            val success = followRepository.updateNewProductNotification(followerId, followingId, enabled)
            if (!success) {
                throw InternalServerException(Errors.Social.Follow.NOTIFICATION_UPDATE_FAILED)
            }

            mapOf("notifyNewProduct" to enabled)
        }
    }
}
