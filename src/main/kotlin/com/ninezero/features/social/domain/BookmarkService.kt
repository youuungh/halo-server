package com.ninezero.features.social.domain

import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.BookmarkOperationFailedException
import com.ninezero.core.common.exception.CommentNotFoundException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.features.social.data.BookmarkRepository
import com.ninezero.features.social.data.CommentRepository
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.LikeRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.presentation.models.response.BookmarkItemResponse
import com.ninezero.features.social.presentation.models.response.BookmarkListResponse
import com.ninezero.features.social.presentation.models.response.BookmarkToggleResponse
import com.ninezero.features.social.batchLoadCommentMediaItems
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.social.toCommentResponse
import com.ninezero.features.social.toPostResponse
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse

class BookmarkService(
    private val bookmarkRepository: BookmarkRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val userRepository: UserRepository,
    private val likeRepository: LikeRepository,
    private val postService: PostService,
    private val followRepository: FollowRepository
) {

    suspend fun togglePostBookmark(userId: Int, postId: Int): BookmarkToggleResponse {
        return query {
            postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

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
    }

    suspend fun toggleCommentBookmark(userId: Int, commentId: Int): BookmarkToggleResponse {
        return query {
            val comment = commentRepository.findCommentById(commentId)
                ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

            // 비밀글 댓글은 참여자만 북마크 가능
            val parentPost = postRepository.findPostById(comment.postId)
            if (parentPost != null && parentPost.isSecret && !postService.checkPostAccess(userId, parentPost)) {
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
        viewerUserId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        targetType: BookmarkTargetType? = null
    ): BookmarkListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val bookmarks = bookmarkRepository.findUserBookmarks(userId, validPage, validLimit, targetType)
            val totalCount = bookmarkRepository.countUserBookmarks(userId, targetType)

            // 타입별 대상 ID 일괄 조회
            val postIds = bookmarks.filter { it.targetType == BookmarkTargetType.POST }.map { it.targetId }
            val commentIds = bookmarks.filter { it.targetType == BookmarkTargetType.COMMENT }.map { it.targetId }

            val postMap = if (postIds.isNotEmpty()) postRepository.findPostsByIds(postIds).associateBy { it.id.value } else emptyMap()
            val postLikeMap = if (postIds.isNotEmpty()) likeRepository.checkMultiplePostLikes(userId, postIds) else emptyMap()
            val postMediaMap = if (postIds.isNotEmpty()) batchLoadPostMediaItems(postIds) else emptyMap()
            val postAccessMap = if (postMap.isNotEmpty()) postService.checkMultiplePostAccess(viewerUserId, postMap.values.toList()) else emptyMap()
            // 포스트 작성자만 팔로우 상태 대상
            val postAuthorFollowMap = followRepository.checkMultipleFollowStatus(viewerUserId, postMap.values.map { it.userId }.distinct())

            val commentMap = if (commentIds.isNotEmpty()) commentRepository.findCommentsByIds(commentIds).associateBy { it.id.value } else emptyMap()
            val commentLikeMap = if (commentIds.isNotEmpty()) likeRepository.checkMultipleCommentLikes(userId, commentIds) else emptyMap()
            val commentMediaMap = if (commentIds.isNotEmpty()) batchLoadCommentMediaItems(commentIds) else emptyMap()
            // 삭제된 부모글도 조회 (비밀글 댓글은 참여자만 목록에 노출)
            val commentParentPosts = if (commentMap.isNotEmpty())
                postRepository.findPostsByIdsWithDeleted(commentMap.values.map { it.postId }.distinct()).associateBy { it.id.value }
            else emptyMap()
            val commentParentAccessMap = if (commentParentPosts.isNotEmpty())
                postService.checkMultiplePostAccess(viewerUserId, commentParentPosts.values.toList())
            else emptyMap()

            val authorIds = (postMap.values.map { it.userId } + commentMap.values.map { it.userId }).distinct()
            val authorMap = if (authorIds.isNotEmpty()) {
                userRepository.findUsersByIds(authorIds).associateBy({ it.id.value }, { it.toSummaryResponse() })
            } else emptyMap()

            val items = bookmarks.mapNotNull { bookmark ->
                val postResponse = if (bookmark.targetType == BookmarkTargetType.POST) {
                    postMap[bookmark.targetId]?.let { post ->
                        val author = authorMap[post.userId]?.copy(isFollowing = postAuthorFollowMap[post.userId]) ?: return@let null
                        post.toPostResponse(
                            author = author,
                            isLiked = postLikeMap[post.id.value] ?: false,
                            isBookmarked = true,
                            canAccess = postAccessMap[post.id.value] ?: (post.requiredTier == SubscriptionPlanTier.FREE && !post.isSecret),
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

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(items, pagination)
        }
    }

    suspend fun isPostBookmarked(userId: Int, postId: Int): Map<String, Boolean> {
        return query {
            val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, postId)
            mapOf("isBookmarked" to isBookmarked)
        }
    }

    suspend fun isCommentBookmarked(userId: Int, commentId: Int): Map<String, Boolean> {
        return query {
            val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.COMMENT, commentId)
            mapOf("isBookmarked" to isBookmarked)
        }
    }

    suspend fun getUserBookmarkCount(userId: Int, targetType: BookmarkTargetType? = null): Map<String, Int> {
        return query {
            val count = bookmarkRepository.countUserBookmarks(userId, targetType)
            mapOf("count" to count)
        }
    }
}
