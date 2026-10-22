package com.ninezero.features.social.data

import com.ninezero.core.common.config.CommunityPostSortType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserPostSortType
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.database.entities.social.PostDao

interface PostRepository {

    // 포스트 생성/수정/삭제
    suspend fun createPost(
        userId: Int,
        content: String,
        postType: PostType,
        tags: List<String> = emptyList(),
        productId: Int? = null,
        requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE,
        contextType: PostContextType = PostContextType.GENERAL,
        creatorId: Int? = null,
        clientRequestId: String? = null
    ): PostDao

    suspend fun createCommunityPost(
        userId: Int,
        creatorId: Int,
        content: String,
        postType: PostType,
        tags: List<String> = emptyList(),
        isSecret: Boolean = false,
        clientRequestId: String? = null
    ): PostDao

    suspend fun updatePost(
        postId: Int,
        content: String,
        tags: List<String> = emptyList()
    ): PostDao?

    suspend fun deletePost(postId: Int): Boolean

    // 크리에이터 해제/복귀
    suspend fun hideCreatorPosts(creatorId: Int): List<Int>
    suspend fun restoreCreatorHiddenPosts(creatorId: Int): List<Int>

    // 탈퇴 정리
    suspend fun findHiddenCreatorPostMediaUrls(creatorId: Int): List<String>

    // 포스트 고정
    suspend fun pinPostInFeed(postId: Int)
    suspend fun unpinPostInFeed(postId: Int)
    suspend fun findPinnedPostsInFeed(creatorId: Int, limit: Int): List<PostDao>

    // 커뮤니티 공지 고정
    suspend fun pinPostInCommunity(postId: Int)
    suspend fun unpinPostInCommunity(postId: Int)
    suspend fun findPinnedPostsInCommunity(creatorId: Int, limit: Int): List<PostDao>

    // 포스트 조회
    suspend fun findPostById(postId: Int): PostDao?
    suspend fun findPostByIdWithDeleted(postId: Int): PostDao?
    suspend fun findPostByClientRequestId(userId: Int, clientRequestId: String): PostDao?
    suspend fun findPostsByIds(postIds: Collection<Int>): List<PostDao>
    suspend fun findPostsByIdsWithDeleted(postIds: Collection<Int>): List<PostDao>
    suspend fun findPostsByUserId(
        userId: Int,
        cursor: PageCursor?,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: UserPostSortType,
        contextType: PostContextType?,
        includeBlindedForAuthor: Boolean
    ): List<PostDao>

    // 피드 조회
    suspend fun findHomeFeedPosts(
        userIds: Collection<Int>,
        cursor: PageCursor?,
        limit: Int,
        excludedAuthorIds: Set<Int>,
        excludedPostIds: Set<Int>
    ): List<PostDao>
    suspend fun findExploreFeedPosts(page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<PostDao>
    suspend fun findTrendingPosts(hours: Int, page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<PostDao>

    // 커뮤니티 조회
    suspend fun findCommunityPosts(
        creatorId: Int,
        cursor: PageCursor?,
        limit: Int,
        excludedAuthorIds: Set<Int>,
        sort: CommunityPostSortType
    ): List<PostDao>

    // 섹션별 조회
    suspend fun findPostsByCreatorAndContext(
        creatorId: Int,
        contextType: PostContextType,
        page: Int,
        limit: Int
    ): List<PostDao>

    // 해시태그 조회
    suspend fun findPostsByHashtag(hashtag: String, page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<PostDao>

    // 관리자
    suspend fun findAllPosts(page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): List<PostDao>

    // 카운트 수정
    suspend fun incrementViewCount(postId: Int): Boolean
    suspend fun incrementLikeCount(postId: Int): Boolean
    suspend fun decrementLikeCount(postId: Int): Boolean
    suspend fun incrementCommentCount(postId: Int): Boolean
    suspend fun decrementCommentCount(postId: Int): Boolean
    suspend fun incrementShareCount(postId: Int): Boolean

    // 카운트
    suspend fun countPinnedPostsInFeed(creatorId: Int): Int
    suspend fun countPinnedPostsInCommunity(creatorId: Int): Int
    suspend fun countUserPosts(
        userId: Int,
        contextType: PostContextType?,
        includeBlindedForAuthor: Boolean
    ): Int
    suspend fun countTrendingPosts(hours: Int): Long
    suspend fun countAllPosts(): Int
    suspend fun countPostsByUserId(userId: Int): Int
    suspend fun countPostsByUserIds(userIds: Collection<Int>): Map<Int, Int>
    suspend fun countPostsByCreatorAndContext(creatorId: Int, contextType: PostContextType): Int
}
