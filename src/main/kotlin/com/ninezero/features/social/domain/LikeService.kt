package com.ninezero.features.social.domain

import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.batchLoadCommentMediaItems
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.social.data.*
import com.ninezero.features.social.presentation.models.response.*
import com.ninezero.features.social.toCommentResponse
import com.ninezero.features.social.toPostResponse
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class LikeService(
    private val likeRepository: LikeRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val bookmarkRepository: BookmarkRepository,
    private val postService: PostService,
    private val followRepository: FollowRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    /** 좋아요 토글 */
    suspend fun togglePostLike(currentUserId: Int, postId: Int): LikeToggleResponse {
        // 미차단일 때만 알림 대상
        var notifyPostOwnerId: Int? = null
        val response = query {
            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            val isCurrentlyLiked = likeRepository.isPostLiked(currentUserId, postId)

            if (isCurrentlyLiked) {
                val deleted = likeRepository.deleteLike(currentUserId, postId, LikeType.POST)
                if (!deleted) {
                    throw LikeOperationFailedException(Errors.Social.Like.UNLIKE_FAILED)
                }

                postRepository.decrementLikeCount(postId)

                LikeToggleResponse(
                    isLiked = false,
                    likeCount = post.likeCount - 1,
                    message = Messages.Social.LIKE_CANCEL_SUCCESS
                )
            } else {
                likeRepository.createPostLike(currentUserId, postId)
                    ?: throw LikeOperationFailedException(Errors.Social.Like.LIKE_FAILED)

                postRepository.incrementLikeCount(postId)

                // 차단 관계면 알림 미발송
                if (!blockedUserRepository.isBlockedEither(currentUserId, post.userId)) {
                    notifyPostOwnerId = post.userId
                }

                LikeToggleResponse(
                    isLiked = true,
                    likeCount = post.likeCount + 1,
                    message = Messages.Social.LIKE_SUCCESS
                )
            }
        }

        notifyPostOwnerId?.let { ownerId ->
            coroutineScope.launch {
                try {
                    notificationService.sendLikePostNotification(ownerId, currentUserId, postId)
                } catch (e: Exception) {
                    logger.error("좋아요 알림 전송 실패 - postId: $postId", e)
                }
            }
        }
        return response
    }

    suspend fun toggleCommentLike(currentUserId: Int, commentId: Int): LikeToggleResponse {
        var notifyCommentOwnerId: Int? = null
        var notifyPostId: Int? = null
        val response = query {
            val comment = commentRepository.findCommentById(commentId)
                ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

            // 비밀글 댓글은 참여자만 좋아요 가능
            val parentPost = postRepository.findPostById(comment.postId)
            if (parentPost != null && parentPost.isSecret && !postService.checkPostAccess(currentUserId, parentPost)) {
                throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
            }

            val isCurrentlyLiked = likeRepository.isCommentLiked(currentUserId, commentId)

            if (isCurrentlyLiked) {
                val deleted = likeRepository.deleteLike(currentUserId, commentId, LikeType.COMMENT)
                if (!deleted) {
                    throw LikeOperationFailedException(Errors.Social.Like.COMMENT_UNLIKE_FAILED)
                }

                commentRepository.decrementLikeCount(commentId)

                LikeToggleResponse(
                    isLiked = false,
                    likeCount = comment.likeCount - 1,
                    message = Messages.Social.COMMENT_LIKE_CANCEL_SUCCESS
                )
            } else {
                likeRepository.createCommentLike(currentUserId, commentId)
                    ?: throw LikeOperationFailedException(Errors.Social.Like.COMMENT_LIKE_FAILED)

                commentRepository.incrementLikeCount(commentId)

                notifyCommentOwnerId = comment.userId
                notifyPostId = comment.postId

                LikeToggleResponse(
                    isLiked = true,
                    likeCount = comment.likeCount + 1,
                    message = Messages.Social.COMMENT_LIKE_SUCCESS
                )
            }
        }

        val ownerId = notifyCommentOwnerId
        val pId = notifyPostId
        if (ownerId != null && pId != null) {
            coroutineScope.launch {  // 커밋 후 알림 발송
                try {
                    notificationService.sendLikeCommentNotification(
                        commentOwnerId = ownerId,
                        likerId = currentUserId,
                        commentId = commentId,
                        postId = pId
                    )
                } catch (e: Exception) {
                    logger.error("댓글 좋아요 알림 전송 실패 - commentId: $commentId", e)
                }
            }
        }
        return response
    }

    // 좋아요 목록·수
    suspend fun getPostLikedUsers(
        postId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): LikeUserResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val likes = likeRepository.findPostLikes(postId, validPage, validLimit)
            val totalCount = likeRepository.countPostLikes(postId)

            val userIds = likes.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val likeUserInfos = likes.map { like ->
                val user = userMap[like.userId]?.toSummaryResponse()
                    ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)

                LikeUserInfoResponse(
                    user = user,
                    likedAt = like.createdAt
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(likeUserInfos, pagination)
        }
    }

    suspend fun getCommentLikedUsers(
        commentId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): LikeUserResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val likes = likeRepository.findCommentLikes(commentId, validPage, validLimit)
            val totalCount = likeRepository.countCommentLikes(commentId)

            val userIds = likes.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val likeUserInfos = likes.map { like ->
                val user = userMap[like.userId]?.toSummaryResponse()
                    ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)

                LikeUserInfoResponse(
                    user = user,
                    likedAt = like.createdAt
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(likeUserInfos, pagination)
        }
    }

    suspend fun getPostLikeCount(postId: Int): Map<String, Int> {
        return query {
            val count = likeRepository.countPostLikes(postId)
            mapOf("count" to count)
        }
    }

    suspend fun getCommentLikeCount(commentId: Int): Map<String, Int> {
        return query {
            val count = likeRepository.countCommentLikes(commentId)
            mapOf("count" to count)
        }
    }

    // 내가 좋아요한 항목
    suspend fun getUserLikedPosts(
        currentUserId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): Map<String, List<Int>> {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val likedPostIds = likeRepository.findUserLikedPosts(currentUserId, validPage, validLimit)
            mapOf("likedPosts" to likedPostIds)
        }
    }

    suspend fun getUserLikedItems(
        currentUserId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        targetType: LikeType? = null
    ): LikedListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val likes = likeRepository.findUserLikes(currentUserId, validPage, validLimit, targetType)
            val totalCount = likeRepository.countUserLikes(currentUserId, targetType)

            // 타입별 대상 ID 일괄 조회
            val postIds = likes.filter { it.targetType == LikeType.POST }.map { it.targetId }
            val commentIds = likes.filter { it.targetType == LikeType.COMMENT }.map { it.targetId }

            val postMap = if (postIds.isNotEmpty()) postRepository.findPostsByIds(postIds).associateBy { it.id.value } else emptyMap()
            val postBookmarkMap = if (postIds.isNotEmpty()) bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.POST, postIds) else emptyMap()
            val postMediaMap = if (postIds.isNotEmpty()) batchLoadPostMediaItems(postIds) else emptyMap()
            val postAccessMap = if (postMap.isNotEmpty()) postService.checkMultiplePostAccess(currentUserId, postMap.values.toList()) else emptyMap()

            val commentMap = if (commentIds.isNotEmpty()) commentRepository.findCommentsByIds(commentIds).associateBy { it.id.value } else emptyMap()
            val commentBookmarkMap = if (commentIds.isNotEmpty()) bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.COMMENT, commentIds) else emptyMap()
            val commentMediaMap = if (commentIds.isNotEmpty()) batchLoadCommentMediaItems(commentIds) else emptyMap()
            // 삭제된 부모글도 조회 (비밀글 댓글은 참여자만 히스토리에 노출)
            val commentParentPosts = if (commentMap.isNotEmpty())
                postRepository.findPostsByIdsWithDeleted(commentMap.values.map { it.postId }.distinct()).associateBy { it.id.value }
            else emptyMap()
            val commentParentAccessMap = if (commentParentPosts.isNotEmpty())
                postService.checkMultiplePostAccess(currentUserId, commentParentPosts.values.toList())
            else emptyMap()

            val authorIds = (postMap.values.map { it.userId } + commentMap.values.map { it.userId }).distinct()
            // 포스트 작성자만 팔로우 여부 대상
            val postAuthorIds = postMap.values.map { it.userId }.distinct()
            val followStatusMap = if (postAuthorIds.isNotEmpty()) followRepository.checkMultipleFollowStatus(currentUserId, postAuthorIds) else emptyMap<Int, Boolean>()
            val authorMap = if (authorIds.isNotEmpty()) {
                userRepository.findUsersByIds(authorIds).associateBy({ it.id.value }, { it.toSummaryResponse(isFollowing = followStatusMap[it.id.value]) })
            } else emptyMap()

            val items = likes.mapNotNull { like ->
                val postResponse = if (like.targetType == LikeType.POST) {
                    postMap[like.targetId]?.let { post ->
                        val author = authorMap[post.userId] ?: return@let null
                        post.toPostResponse(
                            author = author,
                            isLiked = true,
                            isBookmarked = postBookmarkMap[post.id.value] ?: false,
                            canAccess = postAccessMap[post.id.value] ?: (post.requiredTier == SubscriptionPlanTier.FREE && !post.isSecret),
                            mediaItems = postMediaMap[post.id.value] ?: emptyList()
                        )
                    }
                } else null

                val commentResponse = if (like.targetType == LikeType.COMMENT) {
                    commentMap[like.targetId]?.let { comment ->
                        val parentPost = commentParentPosts[comment.postId]
                        // 비참여 비밀글 댓글은 제외
                        if (parentPost != null && parentPost.isSecret && !(commentParentAccessMap[parentPost.id.value] ?: false)) {
                            return@mapNotNull null
                        }
                        val author = authorMap[comment.userId] ?: return@let null
                        comment.toCommentResponse(
                            author = author,
                            isLiked = true,
                            isBookmarked = commentBookmarkMap[comment.id.value] ?: false,
                            mediaItems = commentMediaMap[comment.id.value] ?: emptyList()
                        )
                    }
                } else null

                LikedItemResponse(
                    targetType = like.targetType,
                    targetId = like.targetId,
                    createdAt = like.createdAt,
                    post = postResponse,
                    comment = commentResponse
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(items, pagination)
        }
    }

    // 좋아요 여부
    suspend fun isPostLiked(currentUserId: Int, postId: Int): Map<String, Boolean> {
        return query {
            val isLiked = likeRepository.isPostLiked(currentUserId, postId)
            mapOf("isLiked" to isLiked)
        }
    }

    suspend fun isCommentLiked(currentUserId: Int, commentId: Int): Map<String, Boolean> {
        return query {
            val isLiked = likeRepository.isCommentLiked(currentUserId, commentId)
            mapOf("isLiked" to isLiked)
        }
    }
}
