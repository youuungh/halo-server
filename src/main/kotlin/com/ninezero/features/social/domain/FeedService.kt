package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.FeedType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidHashtagException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.social.createFeedResponse
import com.ninezero.features.social.data.*
import com.ninezero.features.social.presentation.models.request.FeedRequest
import com.ninezero.features.social.presentation.models.response.FeedResponse
import com.ninezero.features.social.toPostResponse
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse
import kotlin.time.Duration.Companion.minutes

class FeedService(
    private val postRepository: PostRepository,
    private val likeRepository: LikeRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val followRepository: FollowRepository,
    private val hiddenPostRepository: HiddenPostRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val userRepository: UserRepository,
    private val postService: PostService,
    private val cacheService: CacheService
) {

    suspend fun getHomeFeed(userId: Int, request: FeedRequest): FeedResponse {
        val (validLimit, validLastPostId) = validateCursorParams(request.limit, request.lastPostId)

        return query {
            val followingIds = followRepository.findFollowingIds(userId)
            val userIdsToShow = followingIds + userId

            val posts = postRepository.findHomeFeedPosts(
                userIds = userIdsToShow,
                page = request.page,
                limit = validLimit,
                lastPostId = validLastPostId
            )

            buildFeedResponse(
                posts = posts,
                userId = userId,
                feedType = FeedType.HOME,
                page = request.page,
                limit = validLimit,
                checkAccess = true
            )
        }
    }

    suspend fun getExploreFeed(userId: Int?, request: FeedRequest): FeedResponse {
        val (validLimit, _) = validateCursorParams(request.limit, null)

        val cacheKey = CacheKeys.exploreFeed(request.page, validLimit, userId)
        cacheService.getJson<FeedResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val posts = postRepository.findExploreFeedPosts(
                userId = userId,
                page = request.page,
                limit = validLimit
            )

            buildFeedResponse(
                posts = posts,
                userId = userId,
                feedType = FeedType.EXPLORE,
                page = request.page,
                limit = validLimit,
                checkAccess = true
            )
        }

        cacheService.setJson(cacheKey, response, ttl = 5.minutes)

        return response
    }

    suspend fun getTrendingFeed(userId: Int?, request: FeedRequest): FeedResponse {
        val (validLimit, _) = validateCursorParams(request.limit, null)

        val cacheKey = CacheKeys.trendingFeed(request.page, validLimit, userId)
        cacheService.getJson<FeedResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            // 후보 부족하면 확장
            val effectiveHours = Constants.Social.TRENDING_FALLBACK_HOURS
                .firstOrNull { hours ->
                    postRepository.countTrendingPosts(hours) >= Constants.Social.TRENDING_MIN_COUNT
                } ?: Constants.Social.TRENDING_FALLBACK_HOURS.last()

            val posts = postRepository.findTrendingPosts(
                hours = effectiveHours,
                page = request.page,
                limit = validLimit
            )

            buildFeedResponse(
                posts = posts,
                userId = userId,
                feedType = FeedType.TRENDING,
                page = request.page,
                limit = validLimit,
                checkAccess = true
            )
        }

        cacheService.setJson(cacheKey, response, ttl = 10.minutes)

        return response
    }

    suspend fun getHashtagFeed(
        hashtag: String,
        userId: Int?,
        request: FeedRequest
    ): FeedResponse {
        if (!isValidHashtag(hashtag)) {
            throw InvalidHashtagException(Errors.Social.Post.INVALID_HASHTAG)
        }

        val (validLimit, _) = validateCursorParams(request.limit, null)

        return query {
            val posts = postRepository.findPostsByHashtag(
                hashtag = hashtag,
                page = request.page,
                limit = validLimit
            )

            buildFeedResponse(
                posts = posts,
                userId = userId,
                feedType = FeedType.HASHTAG,
                page = request.page,
                limit = validLimit,
                checkAccess = true
            )
        }
    }

    suspend fun getPersonalizedFeed(
        userId: Int,
        preferences: List<String> = emptyList(),
        request: FeedRequest
    ): FeedResponse {
        val (validLimit, validLastPostId) = validateCursorParams(request.limit, request.lastPostId)

        return query {
            // TODO: ML 기반 추천 알고리즘 도입
            val posts = if (preferences.isNotEmpty()) {  // 선호 없으면 홈 피드로 폴백
                getPostsByPreferences(userId, preferences, request.page, validLimit)
            } else {
                val followingIds = followRepository.findFollowingIds(userId)
                val userIdsToShow = followingIds + userId

                postRepository.findHomeFeedPosts(
                    userIds = userIdsToShow,
                    page = request.page,
                    limit = validLimit,
                    lastPostId = validLastPostId
                )
            }

            buildFeedResponse(
                posts = posts,
                userId = userId,
                feedType = FeedType.HOME,
                page = request.page,
                limit = validLimit,
                checkAccess = true
            )
        }
    }

    suspend fun getUserFeed(
        targetUserId: Int,
        currentUserId: Int?,
        request: FeedRequest
    ): FeedResponse {
        val (validLimit, _) = validateCursorParams(request.limit, null)

        return query {
            val posts = postRepository.findPostsByUserId(
                userId = targetUserId,
                page = request.page,
                limit = validLimit
            )

            // canAccess로 전달
            val accessMap = if (currentUserId != null && currentUserId != targetUserId) {
                postService.checkMultiplePostAccess(currentUserId, posts)
            } else if (currentUserId == targetUserId) {
                posts.associate { it.id.value to true }
            } else {
                posts.associate { it.id.value to (it.requiredTier == SubscriptionPlanTier.FREE && !it.isSecret) }
            }

            val isFollowingAuthor = if (currentUserId != null && currentUserId != targetUserId) {
                followRepository.isFollowing(currentUserId, targetUserId)
            } else {
                null
            }
            val author = userRepository.findUserById(targetUserId)?.toSummaryResponse(isFollowing = isFollowingAuthor)
                ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)

            val postIds = posts.map { it.id.value }
            val likeStatusMap = if (currentUserId != null) {
                likeRepository.checkMultiplePostLikes(currentUserId, postIds)
            } else {
                emptyMap()
            }
            val bookmarkStatusMap = if (currentUserId != null) {
                bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.POST, postIds)
            } else {
                emptyMap()
            }

            val postMediaMap = batchLoadPostMediaItems(postIds)
            val postResponses = posts.map { post ->
                val isLiked = likeStatusMap[post.id.value] ?: false
                val isBookmarked = bookmarkStatusMap[post.id.value] ?: false
                val canAccess = accessMap[post.id.value] ?: (post.requiredTier == SubscriptionPlanTier.FREE)
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = postMediaMap[post.id.value] ?: emptyList())
            }
            val visiblePostResponses = if (currentUserId == targetUserId) {
                postResponses
            } else {
                postResponses.filter { it.canAccess }  // 타인은 접근권한 없는 글만 필터링
            }

            val hasNext = posts.size >= validLimit
            val cursorInfo = CursorPaginationInfo(
                limit = validLimit,
                lastItemId = visiblePostResponses.lastOrNull()?.id,
                hasNext = hasNext
            )

            createFeedResponse(
                posts = visiblePostResponses,
                feedType = FeedType.USER_POSTS,
                cursorPagination = cursorInfo,
                page = request.page
            )
        }
    }

    suspend fun getFeedStats(userId: Int): Map<String, Any> {
        val cacheKey = CacheKeys.feedStats(userId)
        cacheService.getJson<Map<String, Any>>(cacheKey)?.let {
            return it
        }

        val response = query {
            val followingIds = followRepository.findFollowingIds(userId)
            val userIdsToShow = followingIds + userId

            val homeFeedPosts = postRepository.findHomeFeedPosts(userIdsToShow, 1, 1, lastPostId = null)
            val userPosts = postRepository.findPostsByUserId(userId, 1, 1)
            val userPostCount = postRepository.countPostsByUserId(userId)

            mapOf(
                "homeFeedAvailable" to homeFeedPosts.isNotEmpty(),
                "userPostCount" to userPostCount,
                "hasUserPosts" to userPosts.isNotEmpty(),
                "feedLastUpdated" to System.currentTimeMillis()
            )
        }

        cacheService.setJson(cacheKey, response, ttl = 15.minutes)

        return response
    }

    private suspend fun buildFeedResponse(
        posts: List<PostDao>,
        userId: Int?,
        feedType: FeedType,
        page: Int,
        limit: Int,
        checkAccess: Boolean
    ): FeedResponse {
        val hiddenPostIds = if (userId != null) {
            hiddenPostRepository.findHiddenPostIds(userId).toSet()
        } else {
            emptySet()
        }

        val blockedUserIds = if (userId != null) {
            blockedUserRepository.findBlockRelatedUserIds(userId)
        } else {
            emptySet()
        }

        val visiblePosts = posts.filter { post ->
            post.id.value !in hiddenPostIds && post.userId !in blockedUserIds
        }

        // canAccess로 전달
        val accessMap = if (checkAccess) {
            if (userId != null) {
                postService.checkMultiplePostAccess(userId, visiblePosts)
            } else {
                visiblePosts.associate { it.id.value to (it.requiredTier == SubscriptionPlanTier.FREE && !it.isSecret) }
            }
        } else {
            visiblePosts.associate { it.id.value to true }
        }

        val userIds = visiblePosts.map { it.userId }.distinct()
        val users = userRepository.findUsersByIds(userIds)
        val userMap = users.associateBy { it.id.value }

        val postIds = visiblePosts.map { it.id.value }
        val likeStatusMap = if (userId != null) {
            likeRepository.checkMultiplePostLikes(userId, postIds)
        } else {
            emptyMap()
        }

        val bookmarkStatusMap = if (userId != null) {
            bookmarkRepository.checkMultipleBookmarks(userId, BookmarkTargetType.POST, postIds)
        } else {
            emptyMap()
        }

        val followStatusMap = if (userId != null) {
            followRepository.checkMultipleFollowStatus(userId, userIds)
        } else {
            emptyMap()
        }

        val postMediaMap = batchLoadPostMediaItems(postIds)
        val postResponses = visiblePosts.map { post ->
            val author = userMap[post.userId]?.toSummaryResponse(isFollowing = followStatusMap[post.userId])
                ?: throw UserNotFoundException(Errors.Social.Post.AUTHOR_NOT_FOUND)

            val isLiked = likeStatusMap[post.id.value] ?: false
            val isBookmarked = bookmarkStatusMap[post.id.value] ?: false
            val canAccess = accessMap[post.id.value] ?: (post.requiredTier == SubscriptionPlanTier.FREE)
            post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = postMediaMap[post.id.value] ?: emptyList())
        }

        val hasNext = posts.size >= limit
        val cursorInfo = CursorPaginationInfo(
            limit = limit,
            lastItemId = visiblePosts.lastOrNull()?.id?.value,
            hasNext = hasNext
        )

        return createFeedResponse(
            posts = postResponses,
            feedType = feedType,
            cursorPagination = cursorInfo,
            page = page
        )
    }

    private suspend fun getPostsByPreferences(
        userId: Int,
        preferences: List<String>,
        page: Int,
        limit: Int
    ): List<PostDao> {
        val allPosts = mutableListOf<PostDao>()

        preferences.take(5).forEach { preference -> // 최대 5개 선호도만 사용
            if (isValidHashtag(preference)) {
                val posts = postRepository.findPostsByHashtag(preference, 1, limit / preferences.size + 1)
                allPosts.addAll(posts)
            }
        }

        return allPosts
            .distinctBy { it.id.value }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }
}
