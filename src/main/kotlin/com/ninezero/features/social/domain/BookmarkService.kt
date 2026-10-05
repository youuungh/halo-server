package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.LikeType
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.BookmarkOperationFailedException
import com.ninezero.core.common.exception.CommentNotFoundException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.exception.UserBlockedException
import com.ninezero.core.common.util.CursorResponse
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toCursorPage
import com.ninezero.core.common.util.validateCursorLimit
import com.ninezero.features.social.batchLoadCommentMediaItems
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.social.data.BookmarkRepository
import com.ninezero.features.social.data.CommentRepository
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.LikeRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.presentation.models.response.BookmarkItemResponse
import com.ninezero.features.social.presentation.models.response.BookmarkListResponse
import com.ninezero.features.social.presentation.models.response.BookmarkToggleResponse
import com.ninezero.features.social.toCommentResponse
import com.ninezero.features.social.toPostResponse
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toUserSummaryResponse

class BookmarkService(
    private val bookmarkRepository: BookmarkRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val userRepository: UserRepository,
    private val likeRepository: LikeRepository,
    private val followRepository: FollowRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val postService: PostService,
    private val cacheService: CacheService
) {

    suspend fun togglePostBookmark(userId: Int, postId: Int): BookmarkToggleResponse {
        val response = query {
            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            // 비밀글은 참여자만, 차단 관계는 불가
            if (post.isSecret && !postService.checkPostAccess(userId, post)) {
                throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)
            }
            if (blockedUserRepository.isBlockedEither(userId, post.userId)) {
                throw UserBlockedException(Errors.User.Block.USER_BLOCKED)
            }

            val isCurrentlyBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, postId)

            if (isCurrentlyBookmarked) {
                val deleted = bookmarkRepository.deleteBookmark(userId, BookmarkTargetType.POST, postId)
                if (!deleted) {
                    throw BookmarkOperationFailedException(Errors.Social.Bookmark.UNBOOKMARK_FAILED)
                }

                BookmarkToggleResponse(
                    isBookmarked = false,
                    message = Messages.Social.BOOKMARK_CANCEL_SUCCESS
                )
            } else {
                bookmarkRepository.createBookmark(userId, BookmarkTargetType.POST, postId)
                    ?: throw BookmarkOperationFailedException(Errors.Social.Bookmark.BOOKMARK_FAILED)

                BookmarkToggleResponse(
                    isBookmarked = true,
                    message = Messages.Social.BOOKMARK_SUCCESS
                )
            }
        }

        // 캐시 정리
        cacheService.deletePattern(CacheKeys.Patterns.post(postId))
        cacheService.deletePattern(CacheKeys.Patterns.userScoped(userId))

        return response
    }

    suspend fun toggleCommentBookmark(userId: Int, commentId: Int): BookmarkToggleResponse {
        return query {
            val comment = commentRepository.findCommentById(commentId)
                ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

            if (postService.isSecretPostHiddenFrom(comment.postId, userId)) {  // 비밀글 댓글은 참여자만
                throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
            }

            val isCurrentlyBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.COMMENT, commentId)

            if (isCurrentlyBookmarked) {
                val deleted = bookmarkRepository.deleteBookmark(userId, BookmarkTargetType.COMMENT, commentId)
                if (!deleted) {
                    throw BookmarkOperationFailedException(Errors.Social.Bookmark.UNBOOKMARK_FAILED)
                }

                BookmarkToggleResponse(
                    isBookmarked = false,
                    message = Messages.Social.COMMENT_BOOKMARK_CANCEL_SUCCESS
                )
            } else {
                bookmarkRepository.createBookmark(userId, BookmarkTargetType.COMMENT, commentId)
                    ?: throw BookmarkOperationFailedException(Errors.Social.Bookmark.BOOKMARK_FAILED)

                BookmarkToggleResponse(
                    isBookmarked = true,
                    message = Messages.Social.COMMENT_BOOKMARK_SUCCESS
                )
            }
        }
    }

    suspend fun getUserBookmarks(
        userId: Int,
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        targetType: BookmarkTargetType? = null
    ): BookmarkListResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            // 차단 관계 작성자의 항목은 목록에서 제외
            val blockedUserIds = blockedUserRepository.findBlockRelatedUserIds(userId)
            // 커서는 거르기 전 마지막 행 기준
            val (bookmarks, nextCursor) = bookmarkRepository.findUserBookmarks(userId, pageCursor, validLimit + 1, targetType, blockedUserIds)
                .toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }

            // 타입별 대상 ID 일괄 조회
            val postIds = bookmarks.filter { it.targetType == BookmarkTargetType.POST }.map { it.targetId }
            val commentIds = bookmarks.filter { it.targetType == BookmarkTargetType.COMMENT }.map { it.targetId }

            val postMap = if (postIds.isNotEmpty()) postRepository.findPostsByIds(postIds).associateBy { it.id.value } else emptyMap()
            val postLikeMap = if (postIds.isNotEmpty()) likeRepository.checkMultipleLikes(userId, LikeType.POST, postIds) else emptyMap()
            val postMediaMap = if (postIds.isNotEmpty()) batchLoadPostMediaItems(postIds) else emptyMap()
            val postAccessMap = if (postMap.isNotEmpty()) postService.checkMultiplePostAccess(userId, postMap.values.toList()) else emptyMap()

            val commentMap = if (commentIds.isNotEmpty()) commentRepository.findCommentsByIds(commentIds).associateBy { it.id.value } else emptyMap()
            val commentLikeMap = if (commentIds.isNotEmpty()) likeRepository.checkMultipleLikes(userId, LikeType.COMMENT, commentIds) else emptyMap()
            val commentMediaMap = if (commentIds.isNotEmpty()) batchLoadCommentMediaItems(commentIds) else emptyMap()
            // 삭제된 부모글도 조회
            val commentParentPosts = if (commentMap.isNotEmpty())
                postRepository.findPostsByIdsWithDeleted(commentMap.values.map { it.postId }.distinct()).associateBy { it.id.value }
            else emptyMap()
            val commentParentAccessMap = if (commentParentPosts.isNotEmpty())
                postService.checkMultiplePostAccess(userId, commentParentPosts.values.toList())
            else emptyMap()

            val authorIds = (postMap.values.map { it.userId } + commentMap.values.map { it.userId }).distinct()
            val followStatusMap = if (authorIds.isNotEmpty()) followRepository.checkMultipleFollowStatus(userId, authorIds) else emptyMap()
            val authorMap = if (authorIds.isNotEmpty()) {
                userRepository.findUsersByIds(authorIds).associateBy({ it.id.value }, { it.toUserSummaryResponse(isFollowing = followStatusMap[it.id.value]) })
            } else emptyMap()

            val items = bookmarks.mapNotNull { bookmark ->
                val postResponse = if (bookmark.targetType == BookmarkTargetType.POST) {
                    postMap[bookmark.targetId]?.let { post ->
                        val author = authorMap[post.userId] ?: return@let null
                        post.toPostResponse(
                            author = author,
                            isLiked = postLikeMap[post.id.value] ?: false,
                            isBookmarked = true,
                            canAccess = postAccessMap[post.id.value] ?: false,
                            mediaItems = postMediaMap[post.id.value] ?: emptyList()
                        )
                    }
                } else null

                val commentResponse = if (bookmark.targetType == BookmarkTargetType.COMMENT) {
                    commentMap[bookmark.targetId]?.let { comment ->
                        val parentPost = commentParentPosts[comment.postId]
                        // 비참여 비밀글 댓글은 제외
                        if (parentPost != null && parentPost.isSecret && !(commentParentAccessMap[parentPost.id.value] ?: false)) {
                            return@mapNotNull null
                        }
                        val author = authorMap[comment.userId] ?: return@let null
                        comment.toCommentResponse(
                            author = author,
                            isLiked = commentLikeMap[comment.id.value] ?: false,
                            isBookmarked = true,
                            mediaItems = commentMediaMap[comment.id.value] ?: emptyList()
                        )
                    }
                } else null

                BookmarkItemResponse(
                    targetType = bookmark.targetType,
                    targetId = bookmark.targetId,
                    createdAt = bookmark.createdAt,
                    post = postResponse,
                    comment = commentResponse
                )
            }

            CursorResponse(items, nextCursor, hasNext = nextCursor != null)
        }
    }
}
