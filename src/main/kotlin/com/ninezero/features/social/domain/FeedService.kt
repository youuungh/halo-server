package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.FeedType
import com.ninezero.core.common.config.LikeType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidHashtagException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.social.createFeedResponse
import com.ninezero.features.social.data.*
import com.ninezero.features.social.isValidHashtag
import com.ninezero.features.social.presentation.models.request.FeedRequest
import com.ninezero.features.social.presentation.models.response.FeedResponse
import com.ninezero.features.social.presentation.models.response.PostResponse
import com.ninezero.features.social.toPostResponse
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toUserSummaryResponse
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

    suspend fun getHomeFeed(userId: Int, cursor: String? = null, limit: Int = Constants.DEFAULT_PAGE_LIMIT): CursorResponse<PostResponse> {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            val followingIds = followRepository.findFollowingIds(userId)

            // 숨김·차단 글은 쿼리에서 빼 페이지 채움
            val (posts, nextCursor) = postRepository.findHomeFeedPosts(
                userIds = followingIds + userId,
                cursor = pageCursor,
                limit = validLimit + 1,
                excludedAuthorIds = blockedUserRepository.findBlockRelatedUserIds(userId),
                excludedPostIds = hiddenPostRepository.findHiddenPostIds(userId).toSet()
            ).toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }

            CursorResponse(buildPostResponses(posts, userId), nextCursor, hasNext = nextCursor != null)
        }
    }

    suspend fun getExploreFeed(userId: Int?, request: FeedRequest): FeedResponse {
        val (validPage, validLimit) = validatePaginationParams(request.page, request.limit)

        val cacheKey = CacheKeys.exploreFeed(validPage, validLimit, userId)
        cacheService.getJson<FeedResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val posts = postRepository.findExploreFeedPosts(
                page = validPage,
                limit = validLimit
            )

            buildFeedResponse(
                posts = posts,
                userId = userId,
                feedType = FeedType.EXPLORE,
                page = validPage,
                limit = validLimit
            )
        }

        cacheService.setJson(cacheKey, response, ttl = 5.minutes)

        return response
    }

    suspend fun getTrendingFeed(userId: Int?, request: FeedRequest): FeedResponse {
        val (validPage, validLimit) = validatePaginationParams(request.page, request.limit)

        val cacheKey = CacheKeys.trendingFeed(validPage, validLimit, userId)
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
                page = validPage,
                limit = validLimit
            )

            buildFeedResponse(
                posts = posts,
                userId = userId,
                feedType = FeedType.TRENDING,
                page = validPage,
                limit = validLimit
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

        val (validPage, validLimit) = validatePaginationParams(request.page, request.limit)

        return query {
            val posts = postRepository.findPostsByHashtag(
                hashtag = hashtag,
                page = validPage,
                limit = validLimit
            )

            buildFeedResponse(
                posts = posts,
                userId = userId,
                feedType = FeedType.HASHTAG,
                page = validPage,
                limit = validLimit
            )
        }
    }

    private suspend fun buildFeedResponse(
        posts: List<PostDao>,
        userId: Int?,
        feedType: FeedType,
        page: Int,
        limit: Int
    ): FeedResponse = createFeedResponse(
        posts = buildPostResponses(excludeHiddenAndBlocked(posts, userId), userId),
        feedType = feedType,
        hasNext = posts.size >= limit,
        page = page
    )

    // 숨긴 글·차단 관계 글 제외
    private suspend fun excludeHiddenAndBlocked(posts: List<PostDao>, userId: Int?): List<PostDao> {
        if (userId == null) return posts

        val hiddenPostIds = hiddenPostRepository.findHiddenPostIds(userId).toSet()
        val blockedUserIds = blockedUserRepository.findBlockRelatedUserIds(userId)
        return posts.filter { post ->
            post.id.value !in hiddenPostIds && post.userId !in blockedUserIds
        }
    }

    private suspend fun buildPostResponses(posts: List<PostDao>, userId: Int?): List<PostResponse> {
        // canAccess로 전달
        val accessMap = if (userId != null) {
            postService.checkMultiplePostAccess(userId, posts)
        } else {
            posts.associate {
                it.id.value to TierAccessEvaluator.canAccess(
                    viewerId = null,
                    ownerId = it.userId,
                    creatorId = it.creatorId,
                    requiredTier = it.requiredTier,
                    isSecret = it.isSecret,
                    viewerTier = null
                )
            }
        }

        val userIds = posts.map { it.userId }.distinct()
        val users = userRepository.findUsersByIds(userIds)
        val userMap = users.associateBy { it.id.value }

        val postIds = posts.map { it.id.value }
        val likeStatusMap = if (userId != null) {
            likeRepository.checkMultipleLikes(userId, LikeType.POST, postIds)
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
        return posts.map { post ->
            val author = userMap[post.userId]?.toUserSummaryResponse(isFollowing = followStatusMap[post.userId])
                ?: throw UserNotFoundException(Errors.Social.Post.AUTHOR_NOT_FOUND)

            val isLiked = likeStatusMap[post.id.value] ?: false
            val isBookmarked = bookmarkStatusMap[post.id.value] ?: false
            val canAccess = accessMap[post.id.value] ?: false
            post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = postMediaMap[post.id.value] ?: emptyList())
        }
    }
}
