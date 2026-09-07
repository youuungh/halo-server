package com.ninezero.features.social.data

import com.ninezero.core.common.config.CommunityPostSortType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserPostSortType
import com.ninezero.core.database.entities.social.PostDao

interface PostRepository {

    // 포스트 관리
    suspend fun createPost(
        userId: Int,
        content: String,
        postType: PostType,
        mediaAttachments: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        productId: Int? = null,
        requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE,
        contextType: PostContextType = PostContextType.GENERAL,
        creatorId: Int? = null
    ): PostDao

    suspend fun createCommunityPost(
        userId: Int,
        creatorId: Int,
        content: String,
        postType: PostType,
        mediaAttachments: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        isSecret: Boolean = false
    ): PostDao

    suspend fun updatePost(
        postId: Int,
        content: String,
        mediaAttachments: List<String> = emptyList(),
        tags: List<String> = emptyList()
    ): PostDao?

    suspend fun deletePost(postId: Int): Boolean

    // 크리에이터 해제/복귀
    suspend fun hideCreatorPosts(creatorId: Int): List<Int>
    suspend fun restoreCreatorHiddenPosts(creatorId: Int): List<Int>

    // 탈퇴 정리
    suspend fun findHiddenCreatorPostMediaUrls(creatorId: Int): List<String>

    // 포스트 고정
    suspend fun pinPostInFeed(postId: Int, creatorId: Int): Boolean
    suspend fun unpinPostInFeed(postId: Int): Boolean
    suspend fun findPinnedPostsInFeed(creatorId: Int, limit: Int): List<PostDao>
    suspend fun countPinnedPostsInFeed(creatorId: Int): Int
    suspend fun getNextPinnedOrderInFeed(creatorId: Int): Int

    // 커뮤니티 공지 고정
    suspend fun pinPostInCommunity(postId: Int, creatorId: Int): Boolean
    suspend fun unpinPostInCommunity(postId: Int): Boolean
    suspend fun findPinnedPostsInCommunity(creatorId: Int, limit: Int): List<PostDao>
    suspend fun countPinnedPostsInCommunity(creatorId: Int): Int

    // 포스트 조회
    suspend fun findPostById(postId: Int): PostDao?
    suspend fun findPostsByIds(postIds: List<Int>): List<PostDao>
    suspend fun findPostsByIdsWithDeleted(postIds: List<Int>): List<PostDao>
    suspend fun findPostsByUserId(
        userId: Int,
        page: Int,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: UserPostSortType = UserPostSortType.LATEST,
        contextType: PostContextType? = null,
        includeBlindedForAuthor: Boolean = false
    ): List<PostDao>
    suspend fun countUserPosts(
        userId: Int,
        contextType: PostContextType? = null,
        includeBlindedForAuthor: Boolean = false
    ): Int

    // 피드 조회
    suspend fun findHomeFeedPosts(userIds: List<Int>, page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT, lastPostId: Int?): List<PostDao>
    suspend fun findExploreFeedPosts(userId: Int?, page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<PostDao>
    suspend fun findTrendingPosts(hours: Int = Constants.Social.TRENDING_HOURS, page:Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<PostDao>
    suspend fun countTrendingPosts(hours: Int): Long

    // 커뮤니티 조회
    suspend fun findCommunityPosts(
        creatorId: Int,
        page: Int,
        limit: Int,
        sort: CommunityPostSortType = CommunityPostSortType.LATEST
    ): List<PostDao>

    // 섹션별 조회
    suspend fun findPostsByCreatorAndContext(
        creatorId: Int,
        contextType: PostContextType,
        page: Int,
        limit: Int
    ): List<PostDao>

    // 포스트 검색
    suspend fun findPostsByHashtag(hashtag: String, page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<PostDao>
    suspend fun searchPosts(query: String, page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<PostDao>

    // 관리자
    suspend fun findAllPosts(page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<PostDao>
    suspend fun countAllPosts(): Int

    // 카운트 수정
    suspend fun incrementViewCount(postId: Int): Boolean
    suspend fun incrementLikeCount(postId: Int): Boolean
    suspend fun decrementLikeCount(postId: Int): Boolean
    suspend fun incrementCommentCount(postId: Int): Boolean
    suspend fun decrementCommentCount(postId: Int): Boolean
    suspend fun incrementShareCount(postId: Int): Boolean

    // 카운트
    suspend fun countPostsByUserId(userId: Int): Int
    suspend fun countPostsByUserIds(userIds: List<Int>): Map<Int, Int>
    suspend fun countCommunityPosts(creatorId: Int): Int
}
