package com.ninezero.features.social.domain

import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.config.CommentSortType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.MediaType as SocialMediaType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostStatus
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserCommentSortType
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.CommentMediaDao
import com.ninezero.core.database.entities.social.CommentMediaTable
import com.ninezero.core.database.entities.social.CommentTable
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.BookmarkRepository
import com.ninezero.features.social.data.CommentRepository
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.LikeRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.presentation.models.response.*
import com.ninezero.features.social.batchLoadCommentMediaItems
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.social.resolveMediaEditPlan
import com.ninezero.features.social.toCommentResponse
import com.ninezero.features.social.toMediaItemResponse
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.user.toPreviewResponse
import com.ninezero.features.user.toSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.update

private const val REPLY_PREVIEW_LIMIT = 3

private const val COMMENT_THREAD_MAX_DEPTH = 50

class CommentService(
    private val commentRepository: CommentRepository,
    private val postRepository: PostRepository,
    private val likeRepository: LikeRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val userRepository: UserRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val notificationService: NotificationService,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val videoProcessingService: VideoProcessingService,
    private val coroutineScope: CoroutineScope,
    private val postService: PostService,
    private val followRepository: FollowRepository
) {
    private val logger = logger()

    // 댓글 작성
    private fun scheduleVideoTranscode(jobs: List<Pair<String, ByteArray>>) =
        launchVideoTranscode(coroutineScope, videoProcessingService, fileUploadService, jobs) { url ->
            query { !CommentMediaDao.find { CommentMediaTable.url eq url }.empty() }
        }

    suspend fun createComment(
        userId: Int,
        postId: Int,
        content: String,
        parentCommentId: Int? = null
    ): CommentResponse {
        val safeContent = ValidationUtils.sanitizeHtml(content)

        ValidationUtils.validateCommentContent(safeContent)

        val (commentId, parentCommentOwnerId, postOwnerId) = query {
            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            if (blockedUserRepository.isBlockedEither(userId, post.userId)) {
                throw UserBlockedException(Errors.User.Block.USER_BLOCKED)
            }

            // 작성은 참여자 전용
            if (!postService.checkPostAccess(userId, post)) {
                throw ForbiddenException(Errors.Social.Comment.LOCKED_POST_COMMENT_DENIED)
            }

            var parentOwnerId: Int? = null

            if (parentCommentId != null) {
                val parentComment = commentRepository.findCommentById(parentCommentId)
                    ?: throw CommentNotFoundException(Errors.Social.Comment.PARENT_COMMENT_NOT_FOUND)

                if (parentComment.postId != postId) {
                    throw InvalidInputException(Errors.Social.Comment.INVALID_PARENT_COMMENT)
                }

                if (blockedUserRepository.isBlockedEither(userId, parentComment.userId)) {
                    throw UserBlockedException(Errors.User.Block.USER_BLOCKED)
                }

                parentOwnerId = parentComment.userId
            }

            val createdComment = CommentDao.new {
                this.userId = userId
                this.postId = postId
                this.content = safeContent
                this.parentCommentId = parentCommentId
            }

            postRepository.incrementCommentCount(postId)

            if (parentCommentId != null) {
                commentRepository.incrementRepliesCount(parentCommentId)
            }

            Triple(createdComment.id.value, parentOwnerId, post.userId)
        }

        // 알림 전송
        coroutineScope.launch {
            try {
                if (parentCommentId != null && parentCommentOwnerId != null) {
                    notificationService.sendReplyNotification(
                        commentOwnerId = parentCommentOwnerId,
                        replierId = userId,
                        postId = postId,
                        commentId = commentId,
                        parentCommentId = parentCommentId
                    )
                } else {
                    notificationService.sendCommentNotification(
                        postOwnerId = postOwnerId,
                        commenterId = userId,
                        postId = postId,
                        commentId = commentId
                    )
                }

                val mentions = extractMentions(safeContent)
                if (mentions.isNotEmpty()) {
                    val mentionedUserIds = query {
                        // 멘션 사용자명 일괄 조회
                        val users = userRepository.findUsersByUsernames(mentions)
                            .filter { it.id.value != userId }

                        users.filter { mentionedUser ->
                            !blockedUserRepository.isBlockedEither(userId, mentionedUser.id.value)
                        }.map { it.id.value }
                    }

                    mentionedUserIds.forEach { mentionedUserId ->
                        notificationService.sendMentionNotification(
                            mentionedUserId = mentionedUserId,
                            mentionerId = userId,
                            postId = postId,
                            commentId = commentId,
                            content = safeContent
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("댓글 알림 전송 실패: commentId=$commentId, error=${e.message}", e)
            }
        }

        return query {
            val author = userRepository.findUserById(userId)?.toSummaryResponse()
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)
            val savedComment = commentRepository.findCommentById(commentId)
                ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
            val mediaItems = batchLoadCommentMediaItems(listOf(savedComment.id.value))[savedComment.id.value] ?: emptyList()
            savedComment.toCommentResponse(author = author, isLiked = false, mediaItems = mediaItems)
        }
    }

    // 댓글 조회
    private suspend fun isSecretPostHiddenFrom(post: PostDao?, currentUserId: Int?): Boolean {
        if (post == null || !post.isSecret) return false  // 티어 잠금 글 댓글은 공개
        return currentUserId == null || !postService.checkPostAccess(currentUserId, post)  // 참여자만 열람
    }

    suspend fun getCommentById(commentId: Int, currentUserId: Int? = null): CommentResponse {
        return query {
            val comment = commentRepository.findCommentById(commentId)
                ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

            // 비참여자에겐 존재 은닉
            if (isSecretPostHiddenFrom(postRepository.findPostById(comment.postId), currentUserId)) {
                throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
            }

            val author = userRepository.findUserById(comment.userId)?.toSummaryResponse(
                isFollowing = if (currentUserId != null && currentUserId != comment.userId)
                    followRepository.isFollowing(currentUserId, comment.userId) else null
            ) ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            val isBlocked = if (currentUserId != null) {
                blockedUserRepository.isBlockedEither(currentUserId, comment.userId)
            } else {
                false
            }

            val isLiked = if (currentUserId != null) {
                likeRepository.isCommentLiked(currentUserId, commentId)
            } else {
                false
            }

            val isBookmarked = if (currentUserId != null) {
                bookmarkRepository.isBookmarked(currentUserId, BookmarkTargetType.COMMENT, commentId)
            } else {
                false
            }

            val mediaItems = batchLoadCommentMediaItems(listOf(commentId))[commentId] ?: emptyList()

            comment.toCommentResponse(
                author = author,
                isLiked = isLiked,
                isBookmarked = isBookmarked,
                isBlocked = isBlocked,
                mediaItems = mediaItems
            )
        }
    }

    /** 댓글 조상 체인 조회 */
    suspend fun getCommentThread(commentId: Int, currentUserId: Int? = null): List<CommentResponse> {
        return query {
            val target = commentRepository.findCommentById(commentId)  // 딥링크 직접 진입용
                ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

            // 존재 은닉
            if (isSecretPostHiddenFrom(postRepository.findPostById(target.postId), currentUserId)) {
                throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
            }

            // target → root로 부모 체인 수집
            val chain = mutableListOf<CommentDao>()
            var current: CommentDao? = target
            var depth = 0
            while (current != null && depth < COMMENT_THREAD_MAX_DEPTH) {
                chain.add(0, current)
                current = current.parentCommentId?.let { commentRepository.findCommentById(it) }
                depth++
            }

            // 작성자·좋아요·북마크·미디어 일괄 조회
            val ids = chain.map { it.id.value }
            val userMap = userRepository.findUsersByIds(chain.map { it.userId }.distinct())
                .associateBy { it.id.value }
            val followStatusMap = if (currentUserId != null) {
                followRepository.checkMultipleFollowStatus(currentUserId, chain.map { it.userId }.distinct())
            } else emptyMap()
            val likeStatusMap = if (currentUserId != null) {
                likeRepository.checkMultipleCommentLikes(currentUserId, ids)
            } else emptyMap()
            val bookmarkStatusMap = if (currentUserId != null) {
                bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.COMMENT, ids)
            } else emptyMap()
            val mediaItemsMap = batchLoadCommentMediaItems(ids)
            val blockedUserIds = if (currentUserId != null) {
                blockedUserRepository.findBlockRelatedUserIds(currentUserId)
            } else emptySet()

            chain.map { comment ->
                val author = userMap[comment.userId]?.toSummaryResponse(isFollowing = followStatusMap[comment.userId])
                    ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)
                comment.toCommentResponse(
                    author = author,
                    isLiked = likeStatusMap[comment.id.value] ?: false,
                    isBookmarked = bookmarkStatusMap[comment.id.value] ?: false,
                    isBlocked = comment.userId in blockedUserIds,
                    mediaItems = mediaItemsMap[comment.id.value] ?: emptyList()
                )
            }
        }
    }

    suspend fun getPostComments(
        postId: Int,
        currentUserId: Int? = null,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: CommentSortType = CommentSortType.POPULAR
    ): CommentListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            // 비밀글은 비참여자에게 빈 목록
            if (isSecretPostHiddenFrom(postRepository.findPostById(postId), currentUserId)) {
                return@query createPagedResponse(emptyList(), PaginationInfo(validPage, validLimit, 0))
            }

            val rootComments = commentRepository.findCommentsByPostId(postId, validPage, validLimit, sort, currentUserId)
            val totalCount = commentRepository.countCommentsByPostId(postId, currentUserId)

            if (rootComments.isEmpty()) {
                val pagination = PaginationInfo(validPage, validLimit, totalCount)
                return@query createPagedResponse(emptyList(), pagination)
            }

            val blockedUserIds = if (currentUserId != null) {
                blockedUserRepository.findBlockRelatedUserIds(currentUserId)
            } else {
                emptySet()
            }

            val rootCommentIds = rootComments.map { it.id.value }
            val allReplies = commentRepository.findRepliesByParentIds(rootCommentIds)
            val repliesByParentId = allReplies.groupBy { it.parentCommentId ?: 0 }
            // 미리보기 답글만 일괄 조회
            val previewRepliesByParentId = repliesByParentId.mapValues { (_, list) -> list.take(REPLY_PREVIEW_LIMIT) }
            val previewReplies = previewRepliesByParentId.values.flatten()

            val allUserIds = mutableSetOf<Int>()
            rootComments.forEach { allUserIds.add(it.userId) }
            previewReplies.forEach { allUserIds.add(it.userId) }

            val users = userRepository.findUsersByIds(allUserIds.toList())
            val userMap = users.associateBy { it.id.value }
            val followStatusMap = if (currentUserId != null) {
                followRepository.checkMultipleFollowStatus(currentUserId, allUserIds.toList())
            } else emptyMap()

            val allCommentIds = rootCommentIds + previewReplies.map { it.id.value }
            val likeStatusMap = if (currentUserId != null) {
                likeRepository.checkMultipleCommentLikes(currentUserId, allCommentIds)
            } else {
                emptyMap()
            }

            val bookmarkStatusMap = if (currentUserId != null) {
                bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.COMMENT, allCommentIds)
            } else {
                emptyMap()
            }

            val mediaItemsMap = batchLoadCommentMediaItems(allCommentIds)

            val commentResponses = rootComments.map { comment ->
                val author = userMap[comment.userId]?.toSummaryResponse(isFollowing = followStatusMap[comment.userId])
                    ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

                val isLiked = likeStatusMap[comment.id.value] ?: false
                val isBlocked = comment.userId in blockedUserIds

                // 답글은 미리보기 슬라이스만 렌더
                val replies = previewRepliesByParentId[comment.id.value] ?: emptyList()
                val replyCount = repliesByParentId[comment.id.value]?.size ?: 0
                val firstReplyComment = replies.firstOrNull()

                val firstReply = firstReplyComment?.let { reply ->
                    val replyAuthor = userMap[reply.userId]?.toSummaryResponse(isFollowing = followStatusMap[reply.userId])
                        ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)
                    val replyIsLiked = likeStatusMap[reply.id.value] ?: false
                    val replyIsBookmarked = bookmarkStatusMap[reply.id.value] ?: false
                    val replyIsBlocked = reply.userId in blockedUserIds
                    reply.toCommentResponse(author = replyAuthor, isLiked = replyIsLiked, isBookmarked = replyIsBookmarked, isBlocked = replyIsBlocked, mediaItems = mediaItemsMap[reply.id.value] ?: emptyList())
                }

                val hasMoreReplies = replyCount > 1
                val moreReplyCount = if (hasMoreReplies) replyCount - 1 else 0

                val replyPreviewUsers = if (hasMoreReplies) {
                    replies.drop(1).take(2).mapNotNull { reply ->
                        userMap[reply.userId]?.toPreviewResponse()
                    }
                } else {
                    emptyList()
                }

                val isBookmarked = bookmarkStatusMap[comment.id.value] ?: false
                comment.toCommentResponse(
                    author = author,
                    isLiked = isLiked,
                    isBookmarked = isBookmarked,
                    isBlocked = isBlocked,
                    firstReply = firstReply,
                    hasMoreReplies = hasMoreReplies,
                    moreReplyCount = moreReplyCount,
                    replyPreviewUsers = replyPreviewUsers,
                    mediaItems = mediaItemsMap[comment.id.value] ?: emptyList()
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(commentResponses, pagination)
        }
    }

    suspend fun getCommentReplies(
        commentId: Int,
        currentUserId: Int? = null,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: CommentSortType = CommentSortType.POPULAR
    ): CommentListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            // 비밀글 댓글의 대댓글도 비참여자에게 빈 목록
            val parentComment = commentRepository.findCommentById(commentId)
            if (parentComment != null &&
                isSecretPostHiddenFrom(postRepository.findPostById(parentComment.postId), currentUserId)
            ) {
                return@query createPagedResponse(emptyList(), PaginationInfo(validPage, validLimit, 0))
            }

            val replies = commentRepository.findRepliesByCommentId(commentId, validPage, validLimit, sort, currentUserId)
            val totalCount = commentRepository.countRepliesByCommentId(commentId, currentUserId)

            if (replies.isEmpty()) {
                val pagination = PaginationInfo(validPage, validLimit, totalCount)
                return@query createPagedResponse(emptyList(), pagination)
            }

            val blockedUserIds = if (currentUserId != null) {
                blockedUserRepository.findBlockRelatedUserIds(currentUserId)
            } else {
                emptySet()
            }

            val replyIds = replies.map { it.id.value }
            val allSubReplies = commentRepository.findRepliesByParentIds(replyIds)
            val subRepliesByParentId = allSubReplies.groupBy { it.parentCommentId ?: 0 }
            // 미리보기 하위답글만 일괄 조회
            val previewSubRepliesByParentId = subRepliesByParentId.mapValues { (_, list) -> list.take(REPLY_PREVIEW_LIMIT) }
            val previewSubReplies = previewSubRepliesByParentId.values.flatten()

            val allUserIds = mutableSetOf<Int>()
            replies.forEach { allUserIds.add(it.userId) }
            previewSubReplies.forEach { allUserIds.add(it.userId) }

            val users = userRepository.findUsersByIds(allUserIds.toList())
            val userMap = users.associateBy { it.id.value }
            val followStatusMap = if (currentUserId != null) {
                followRepository.checkMultipleFollowStatus(currentUserId, allUserIds.toList())
            } else emptyMap()

            val allCommentIds = replyIds + previewSubReplies.map { it.id.value }
            val likeStatusMap = if (currentUserId != null) {
                likeRepository.checkMultipleCommentLikes(currentUserId, allCommentIds)
            } else {
                emptyMap()
            }

            val bookmarkStatusMap = if (currentUserId != null) {
                bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.COMMENT, allCommentIds)
            } else {
                emptyMap()
            }

            val mediaItemsMap = batchLoadCommentMediaItems(allCommentIds)

            val replyResponses = replies.map { reply ->
                val author = userMap[reply.userId]?.toSummaryResponse(isFollowing = followStatusMap[reply.userId])
                    ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

                val isLiked = likeStatusMap[reply.id.value] ?: false
                val isBookmarked = bookmarkStatusMap[reply.id.value] ?: false
                val isBlocked = reply.userId in blockedUserIds

                // 대답글은 미리보기 슬라이스만 렌더
                val subReplies = previewSubRepliesByParentId[reply.id.value] ?: emptyList()
                val subReplyCount = subRepliesByParentId[reply.id.value]?.size ?: 0
                val firstSubReplyComment = subReplies.firstOrNull()

                val firstReply = firstSubReplyComment?.let { subReply ->
                    val subReplyAuthor = userMap[subReply.userId]?.toSummaryResponse(isFollowing = followStatusMap[subReply.userId])
                        ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)
                    val subReplyIsLiked = likeStatusMap[subReply.id.value] ?: false
                    val subReplyIsBookmarked = bookmarkStatusMap[subReply.id.value] ?: false
                    val subReplyIsBlocked = subReply.userId in blockedUserIds
                    subReply.toCommentResponse(
                        author = subReplyAuthor,
                        isLiked = subReplyIsLiked,
                        isBookmarked = subReplyIsBookmarked,
                        isBlocked = subReplyIsBlocked,
                        mediaItems = mediaItemsMap[subReply.id.value] ?: emptyList()
                    )
                }

                val hasMoreReplies = subReplyCount > 1
                val moreReplyCount = if (hasMoreReplies) subReplyCount - 1 else 0

                val replyPreviewUsers = if (hasMoreReplies) {
                    subReplies.drop(1).take(2).mapNotNull { subReply ->
                        userMap[subReply.userId]?.toPreviewResponse()
                    }
                } else {
                    emptyList()
                }

                reply.toCommentResponse(
                    author = author,
                    isLiked = isLiked,
                    isBookmarked = isBookmarked,
                    isBlocked = isBlocked,
                    firstReply = firstReply,
                    hasMoreReplies = hasMoreReplies,
                    moreReplyCount = moreReplyCount,
                    replyPreviewUsers = replyPreviewUsers,
                    mediaItems = mediaItemsMap[reply.id.value] ?: emptyList()
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(replyResponses, pagination)
        }
    }

    // 댓글 수정/삭제
    suspend fun updateComment(
        commentId: Int,
        userId: Int,
        content: String
    ): CommentResponse {
        val safeContent = ValidationUtils.sanitizeHtml(content)
        ValidationUtils.validateCommentContent(safeContent)

        return query {
            val existingComment = commentRepository.findCommentById(commentId)
                ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

            if (existingComment.userId != userId) {  // 작성자만
                throw PermissionDeniedException(Errors.Social.Comment.COMMENT_UPDATE_PERMISSION_DENIED)
            }

            val updatedComment = commentRepository.updateComment(commentId, safeContent)
                ?: throw CommentUpdateFailedException(Errors.Social.Comment.COMMENT_UPDATE_FAILED)

            val author = userRepository.findUserById(userId)?.toSummaryResponse()
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            val isLiked = likeRepository.isCommentLiked(userId, updatedComment.id.value)
            val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.COMMENT, updatedComment.id.value)
            val mediaItems = batchLoadCommentMediaItems(listOf(updatedComment.id.value))[updatedComment.id.value] ?: emptyList()

            updatedComment.toCommentResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
        }
    }

    /** 댓글 삭제와 미디어 회수 */
    suspend fun deleteComment(commentId: Int, userId: Int): String {
        val mediaAttachments = query {
            val comment = commentRepository.findCommentById(commentId)
                ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

            if (comment.userId != userId) {  // 작성자만
                throw PermissionDeniedException(Errors.Social.Comment.COMMENT_DELETE_PERMISSION_DENIED)
            }

            val postId = comment.postId
            val parentId = comment.parentCommentId

            CommentTable.update({ CommentTable.id eq commentId }) {
                it[isActive] = false
            }

            postRepository.decrementCommentCount(postId)

            if (parentId != null) {
                commentRepository.decrementRepliesCount(parentId)
            }

            // 원본 + 썸네일 수거
            val urls = CommentMediaDao.find { CommentMediaTable.commentId eq commentId }
                .flatMap { listOfNotNull(it.url, it.thumbnailUrl) }
                .filterNot { fileUploadService.isSharedSeedFile(it) }
            CommentMediaDao.find { CommentMediaTable.commentId eq commentId }.forEach { it.delete() }
            urls
        }

        mediaAttachments.forEach { mediaUrl ->
            try {
                fileUploadService.deleteFileIfSupabase(mediaUrl)
            } catch (e: Exception) {
                logger.warn("댓글 삭제 중 미디어 파일 삭제 실패: {} - {}", mediaUrl, e.message)
            }
        }

        return Messages.Social.COMMENT_DELETED
    }

    // 사용자 댓글 스레드
    suspend fun getUserCommentThreads(
        userId: Int,
        currentUserId: Int?,
        page: Int,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: UserCommentSortType = UserCommentSortType.LATEST,
        contextType: PostContextType? = null
    ): UserCommentThreadsResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            // 블라인드 댓글은 본인 답글 탭에서만 노출
            val viewerId = currentUserId?.takeIf { it == userId }

            val userComments = commentRepository.findUserCommentThreads(
                userId = userId,
                page = validPage,
                limit = validLimit,
                sort = sort,
                contextType = contextType,
                viewerId = viewerId
            )

            if (userComments.isEmpty()) {
                val emptyPagination = PaginationInfo(validPage, validLimit, 0)
                return@query UserCommentThreadsResponse(
                    threads = emptyList(),
                    totalCount = emptyPagination.totalCount,
                    page = emptyPagination.page,
                    totalPages = emptyPagination.totalPages,
                    hasNext = emptyPagination.hasNext,
                    hasPrevious = emptyPagination.hasPrevious
                )
            }

            // 직계 부모 댓글만 조회
            val parentIds = userComments.mapNotNull { it.parentCommentId }.distinct()
            val parentComments = if (parentIds.isNotEmpty()) {
                commentRepository.findCommentsByIdsWithDeleted(parentIds)
            } else {
                emptyList()
            }
            val parentCommentMap = parentComments.associateBy { it.id.value }

            val blockedUserIds = if (currentUserId != null) {
                blockedUserRepository.findBlockRelatedUserIds(currentUserId)
            } else {
                emptySet()
            }

            val allComments = userComments + parentComments

            val postIds = allComments.map { it.postId }.distinct()
            val posts = postRepository.findPostsByIdsWithDeleted(postIds)
            val postMap = posts.associateBy { it.id.value }

            val allUserIds = mutableSetOf<Int>()
            allComments.forEach { allUserIds.add(it.userId) }
            posts.forEach { post ->
                allUserIds.add(post.userId)
                post.creatorId?.let { allUserIds.add(it) }
            }
            val users = userRepository.findUsersByIds(allUserIds.toList())
            val userMap = users.associateBy { it.id.value }
            val followStatusMap = if (currentUserId != null) {
                followRepository.checkMultipleFollowStatus(currentUserId, allUserIds.toList())
            } else emptyMap()

            val allCommentIds = allComments.map { it.id.value }
            val likeStatusMap = if (currentUserId != null) {
                likeRepository.checkMultipleCommentLikes(currentUserId, allCommentIds)
            } else {
                emptyMap()
            }

            val commentBookmarkStatusMap = if (currentUserId != null) {
                bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.COMMENT, allCommentIds)
            } else {
                emptyMap()
            }

            val postLikeStatusMap = if (currentUserId != null) {
                likeRepository.checkMultiplePostLikes(currentUserId, postIds)
            } else {
                emptyMap()
            }
            val postBookmarkStatusMap = if (currentUserId != null) {
                bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.POST, postIds)
            } else {
                emptyMap()
            }

            val commentMediaItemsMap = batchLoadCommentMediaItems(allCommentIds)
            val postMediaItemsMap = batchLoadPostMediaItems(postIds)

            // 접근 권한 없으면 excerpt/미디어 제거
            val postAccessMap: Map<Int, Boolean> = if (currentUserId != null) {
                postService.checkMultiplePostAccess(currentUserId, posts)
            } else {
                posts.associate { it.id.value to (it.requiredTier == SubscriptionPlanTier.FREE && !it.isSecret) }
            }

            val threads = userComments.mapNotNull { userComment ->
                val post = postMap[userComment.postId] ?: return@mapNotNull null

                val canAccessPost = postAccessMap[post.id.value]
                    ?: (post.requiredTier == SubscriptionPlanTier.FREE && !post.isSecret)
                // 비밀글 스레드는 비참여자에게 완전 제외
                if (post.isSecret && !canAccessPost) return@mapNotNull null

                val threadCommentsList = mutableListOf<ThreadCommentResponse>()

                userComment.parentCommentId?.let { parentId ->
                    val parent = parentCommentMap[parentId] ?: return@let
                    val parentAuthor = userMap[parent.userId]?.toSummaryResponse(isFollowing = followStatusMap[parent.userId])
                        ?: return@mapNotNull null
                    val isParentBlocked = parent.userId in blockedUserIds
                    val isParentDeleted = !parent.isActive

                    threadCommentsList.add(
                        ThreadCommentResponse(
                            id = parent.id.value,
                            content = if (isParentBlocked || isParentDeleted) "" else parent.content,
                            mediaItems = if (isParentBlocked || isParentDeleted) {
                                emptyList()
                            } else {
                                commentMediaItemsMap[parent.id.value] ?: emptyList()
                            },
                            author = parentAuthor,
                            replyTo = null,
                            createdAt = parent.createdAt,
                            likeCount = parent.likeCount,
                            replyCount = parent.replyCount,
                            isLiked = likeStatusMap[parent.id.value] ?: false,
                            isBookmarked = commentBookmarkStatusMap[parent.id.value] ?: false,
                            isProfileUser = parent.userId == userId,
                            isBlocked = isParentBlocked,
                            isDeleted = isParentDeleted
                        )
                    )
                }

                val userCommentAuthor = userMap[userComment.userId]?.toSummaryResponse(isFollowing = followStatusMap[userComment.userId])
                    ?: return@mapNotNull null

                val userCommentReplyTo = userComment.parentCommentId?.let { parentId ->
                    parentCommentMap[parentId]?.let { parent ->
                        userMap[parent.userId]?.toSummaryResponse(isFollowing = followStatusMap[parent.userId])
                    }
                }

                threadCommentsList.add(
                    ThreadCommentResponse(
                        id = userComment.id.value,
                        content = userComment.content,
                        mediaItems = commentMediaItemsMap[userComment.id.value] ?: emptyList(),
                        author = userCommentAuthor,
                        replyTo = userCommentReplyTo,
                        createdAt = userComment.createdAt,
                        likeCount = userComment.likeCount,
                        replyCount = userComment.replyCount,
                        isLiked = likeStatusMap[userComment.id.value] ?: false,
                        isBookmarked = commentBookmarkStatusMap[userComment.id.value] ?: false,
                        isProfileUser = true,
                        isBlocked = false,
                        isBlinded = userComment.isBlinded
                    )
                )

                // HIDDEN은 삭제와 구분해 표기
                val isPostHidden = !post.isActive && post.status == PostStatus.HIDDEN
                val isPostDeleted = !post.isActive && !isPostHidden
                val postAuthor = userMap[post.userId]?.toSummaryResponse(isFollowing = followStatusMap[post.userId])
                    ?: return@mapNotNull null

                // 삭제/숨김/잠금이면 excerpt/미디어 제거
                val hideContent = isPostDeleted || isPostHidden || !canAccessPost
                val postSummary = CommentPostSummaryResponse(
                    id = post.id.value,
                    contextType = post.contextType,
                    excerpt = if (hideContent) "" else if (post.content.length > 100) {
                        post.content.substring(0, 100) + "..."
                    } else {
                        post.content
                    },
                    mediaItems = if (hideContent) {
                        emptyList()
                    } else {
                        postMediaItemsMap[post.id.value] ?: emptyList()
                    },
                    author = postAuthor,
                    creator = post.creatorId?.let { userMap[it]?.toSummaryResponse(isFollowing = followStatusMap[it]) },
                    likeCount = if (isPostDeleted || isPostHidden) 0 else post.likeCount,
                    commentCount = if (isPostDeleted || isPostHidden) 0 else post.commentCount,
                    isLiked = if (isPostDeleted || isPostHidden) false else postLikeStatusMap[post.id.value] ?: false,
                    isBookmarked = if (isPostDeleted || isPostHidden) false else postBookmarkStatusMap[post.id.value] ?: false,
                    isDeleted = isPostDeleted,
                    isHidden = isPostHidden,
                    requiredTier = post.requiredTier,
                    isSecret = post.isSecret,
                    canAccess = canAccessPost,
                    createdAt = post.createdAt
                )

                CommentThreadResponse(
                    comments = threadCommentsList,
                    post = postSummary
                )
            }

            val totalCount = commentRepository.countUserComments(userId, contextType, viewerId)
            val pagination = PaginationInfo(validPage, validLimit, totalCount)

            UserCommentThreadsResponse(
                threads = threads,
                totalCount = pagination.totalCount,
                page = pagination.page,
                totalPages = pagination.totalPages,
                hasNext = pagination.hasNext,
                hasPrevious = pagination.hasPrevious
            )
        }
    }

    // 댓글 미디어
    suspend fun uploadCommentMedia(
        commentId: Int,
        userId: Int,
        mediaFiles: List<ByteArray>,
        contentTypes: List<String>,
        mediaType: PostType
    ): List<MediaItemResponse> {
        val uploadedNewFiles = mutableListOf<String>()

        try {
            ValidationUtils.validateUploadedMediaFiles(mediaFiles, contentTypes)

            val maxSize = StorageConfig.FileSizeLimit.COMMENT
            val isValid = when (mediaType) {
                PostType.IMAGE -> mediaFiles.all { imageProcessingService.validateImage(it, maxSize) }
                PostType.VIDEO -> mediaFiles.all { imageProcessingService.validateVideo(it, maxSize) }
                else -> throw InvalidInputException(Errors.Social.Post.INVALID_MEDIA_TYPE)
            }

            if (!isValid) {
                throw when (mediaType) {
                    PostType.IMAGE -> ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
                    PostType.VIDEO -> ValidationException(Errors.File.MEDIA_VALIDATION_FAILED)
                    else -> InvalidFileException(Errors.File.INVALID_MEDIA_FILE)
                }
            }

            val currentCount = query {
                val comment = commentRepository.findCommentById(commentId)
                    ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

                if (comment.userId != userId) {  // 작성자만
                    throw PermissionDeniedException(Errors.Common.UNAUTHORIZED)
                }

                CommentMediaDao.find { CommentMediaTable.commentId eq commentId }.count().toInt()
            }

            // 첨부 개수 제한
            if (currentCount + mediaFiles.size > Constants.Social.MAX_MEDIA_ATTACHMENTS_PER_POST) {
                throw InvalidInputException(Errors.Social.Media.ATTACHMENT_LIMIT_EXCEEDED)
            }

            // faststart만 동기
            val extensions = contentTypes.map { imageProcessingService.getFileExtension(it) }
            val filesToUpload = if (mediaType == PostType.VIDEO) {
                mediaFiles.map { videoProcessingService.optimizeForStreaming(it) ?: it }
            } else {
                mediaFiles
            }
            val newMediaAttachments = fileUploadService.uploadCommentMediaList(commentId, filesToUpload, extensions)
            uploadedNewFiles.addAll(newMediaAttachments)

            // 미디어별 메타/썸네일 추출
            val mediaExtras = mediaFiles.map { bytes ->
                when (mediaType) {
                    PostType.VIDEO -> {
                        val meta = videoProcessingService.extractMetadata(bytes)
                        val thumbUrl = videoProcessingService.extractThumbnail(bytes)?.let { thumb ->
                            runCatching { fileUploadService.uploadCommentThumbnail(commentId, thumb) }.getOrNull()
                        }
                        meta to thumbUrl
                    }
                    else -> {
                        val thumbUrl = imageProcessingService.generateDisplayImage(bytes)?.let { display ->
                            runCatching { fileUploadService.uploadCommentThumbnail(commentId, display) }.getOrNull()
                        }
                        null to thumbUrl
                    }
                }
            }
            // 롤백 대상에 썸네일도 포함
            uploadedNewFiles.addAll(mediaExtras.mapNotNull { it.second })

            val response = query {
                newMediaAttachments.mapIndexed { index, url ->
                    createCommentMediaItem(
                        commentId = commentId,
                        fileBytes = mediaFiles[index],
                        mediaType = when (mediaType) {
                            PostType.IMAGE -> SocialMediaType.IMAGE
                            PostType.VIDEO -> SocialMediaType.VIDEO
                            else -> throw InvalidInputException(Errors.Social.Post.INVALID_MEDIA_TYPE)
                        },
                        url = url,
                        sortOrder = currentCount + index,
                        videoMeta = mediaExtras[index].first,
                        thumbnailUrl = mediaExtras[index].second
                    ).toMediaItemResponse()
                }
            }

            if (mediaType == PostType.VIDEO) {
                scheduleVideoTranscode(newMediaAttachments.zip(mediaFiles))
            }

            return response
        } catch (e: Exception) {
            uploadedNewFiles.forEach { url ->  // 실패 시 업로드분 롤백
                fileUploadService.deleteFileIfSupabase(url)
            }
            throw e
        }
    }

    /** 미디어 포함 댓글 수정 */
    suspend fun updateCommentWithMedia(
        commentId: Int,
        userId: Int,
        content: String?,
        keepMediaIds: List<Int>?,
        newFiles: List<ByteArray>?,
        newContentTypes: List<String>?,
        reorder: List<Int>?
    ): CommentResponse {
        val uploadedNewFiles = mutableListOf<String>()
        val mediaAttachmentsToDelete = mutableListOf<String>()
        // 새 미디어 메타/썸네일
        var newMediaExtras: List<Pair<VideoProcessingService.VideoMetadata?, String?>> = emptyList()
        var videoTranscodeJobs: List<Pair<String, ByteArray>> = emptyList()

        try {
            val finalContent = content?.let { ValidationUtils.sanitizeHtml(it) }
            finalContent?.let { ValidationUtils.validateCommentContent(it) }

            if (!newFiles.isNullOrEmpty() && !newContentTypes.isNullOrEmpty() && newFiles.size != newContentTypes.size) {
                throw InvalidFileException("파일 개수와 콘텐츠 타입 개수가 일치하지 않습니다.")
            }

            val (orderedKeepItemIds, contentToUse, deleteItemIds) = query {
                val comment = commentRepository.findCommentById(commentId)
                    ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

                if (comment.userId != userId) {  // 작성자만
                    throw PermissionDeniedException(Errors.Social.Comment.COMMENT_UPDATE_PERMISSION_DENIED)
                }

                val currentItems = CommentMediaDao.find { CommentMediaTable.commentId eq commentId }
                    .orderBy(CommentMediaTable.sortOrder to SortOrder.ASC)
                    .toList()

                val plan = resolveMediaEditPlan(currentItems, keepMediaIds, reorder, newFiles?.size ?: 0) { it.sortOrder }

                // 원본 + 썸네일 수거
                mediaAttachmentsToDelete.addAll(
                    plan.deleteItems.flatMap { listOfNotNull(it.url, it.thumbnailUrl) }
                        .filterNot { fileUploadService.isSharedSeedFile(it) }
                )

                Triple(
                    plan.orderedKeepIds,
                    finalContent ?: comment.content,
                    plan.deleteItems.map { it.id.value }
                )
            }

            if (!newFiles.isNullOrEmpty() && !newContentTypes.isNullOrEmpty()) {
                val maxSize = StorageConfig.FileSizeLimit.COMMENT
                newFiles.forEachIndexed { index, file ->
                    val mediaType = inferMediaType(newContentTypes[index])
                    val valid = when (mediaType) {
                        SocialMediaType.IMAGE -> imageProcessingService.validateImage(file, maxSize)
                        SocialMediaType.VIDEO -> imageProcessingService.validateVideo(file, maxSize)
                    }
                    if (!valid) {
                        throw when (mediaType) {
                            SocialMediaType.IMAGE -> ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
                            SocialMediaType.VIDEO -> ValidationException(Errors.File.MEDIA_VALIDATION_FAILED)
                        }
                    }
                }

                val extensions = newContentTypes.map { imageProcessingService.getFileExtension(it) }
                // faststart만 동기
                val filesToUpload = newFiles.mapIndexed { index, bytes ->
                    if (inferMediaType(newContentTypes[index]) == SocialMediaType.VIDEO) {
                        videoProcessingService.optimizeForStreaming(bytes) ?: bytes
                    } else {
                        bytes
                    }
                }
                val urls = fileUploadService.uploadCommentMediaList(commentId, filesToUpload, extensions)
                uploadedNewFiles.addAll(urls)
                videoTranscodeJobs = urls.zip(newFiles).filterIndexed { index, _ ->
                    inferMediaType(newContentTypes[index]) == SocialMediaType.VIDEO
                }

                // 새 미디어 메타/썸네일 추출
                newMediaExtras = newFiles.mapIndexed { index, bytes ->
                    if (inferMediaType(newContentTypes[index]) == SocialMediaType.VIDEO) {
                        val meta = videoProcessingService.extractMetadata(bytes)
                        val thumbUrl = videoProcessingService.extractThumbnail(bytes)?.let { thumb ->
                            runCatching { fileUploadService.uploadCommentThumbnail(commentId, thumb) }.getOrNull()
                        }
                        meta to thumbUrl
                    } else {
                        val thumbUrl = imageProcessingService.generateDisplayImage(bytes)?.let { display ->
                            runCatching { fileUploadService.uploadCommentThumbnail(commentId, display) }.getOrNull()
                        }
                        null to thumbUrl
                    }
                }
            }

            val response = query {
                val existingComment = commentRepository.findCommentById(commentId)
                    ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

                if (existingComment.userId != userId) {
                    throw PermissionDeniedException(Errors.Social.Comment.COMMENT_UPDATE_PERMISSION_DENIED)
                }

                val updatedComment = commentRepository.updateComment(commentId, contentToUse)
                    ?: throw CommentUpdateFailedException(Errors.Social.Comment.COMMENT_UPDATE_FAILED)

                orderedKeepItemIds.forEachIndexed { index, mediaId ->
                    val media = CommentMediaDao.findById(mediaId)
                        ?: throw InvalidInputException(Errors.Social.Media.KEEP_MEDIA_NOT_FOUND)
                    media.sortOrder = index
                }

                if (!newFiles.isNullOrEmpty() && !newContentTypes.isNullOrEmpty()) {
                    uploadedNewFiles.forEachIndexed { index, url ->
                        createCommentMediaItem(
                            commentId = commentId,
                            fileBytes = newFiles[index],
                            mediaType = inferMediaType(newContentTypes[index]),
                            url = url,
                            sortOrder = orderedKeepItemIds.size + index,
                            videoMeta = newMediaExtras.getOrNull(index)?.first,
                            thumbnailUrl = newMediaExtras.getOrNull(index)?.second
                        )
                    }
                }

                deleteItemIds.forEach { mediaId ->
                    CommentMediaDao.findById(mediaId)?.delete()
                }

                val author = userRepository.findUserById(userId)?.toSummaryResponse()
                    ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

                val isLiked = likeRepository.isCommentLiked(userId, updatedComment.id.value)
                val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.COMMENT, updatedComment.id.value)
                val mediaItems = batchLoadCommentMediaItems(listOf(updatedComment.id.value))[updatedComment.id.value] ?: emptyList()
                updatedComment.toCommentResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
            }

            scheduleVideoTranscode(videoTranscodeJobs)

            mediaAttachmentsToDelete.forEach { mediaUrl ->  // 커밋 후 구 파일 삭제
                try {
                    fileUploadService.deleteFileIfSupabase(mediaUrl)
                } catch (e: Exception) {
                    logger.warn("댓글 미디어 삭제 실패: {} - {}", mediaUrl, e.message)
                }
            }

            return response
        } catch (e: Exception) {
            // 파생물은 별도 합산해 롤백 회수
            (uploadedNewFiles + newMediaExtras.mapNotNull { it.second }).forEach { url ->  // 실패 시 신규 업로드분 롤백
                fileUploadService.deleteFileIfSupabase(url)
            }
            throw e
        }
    }

    private fun createCommentMediaItem(
        commentId: Int,
        fileBytes: ByteArray,
        mediaType: SocialMediaType,
        url: String,
        sortOrder: Int,
        videoMeta: VideoProcessingService.VideoMetadata? = null,
        thumbnailUrl: String? = null
    ): CommentMediaDao {
        val dimensions = if (mediaType == SocialMediaType.IMAGE) {
            imageProcessingService.extractImageDimensions(fileBytes)
        } else {
            null
        }

        return CommentMediaDao.new {
            this.commentId = commentId
            this.type = mediaType
            this.url = url
            this.width = videoMeta?.width ?: dimensions?.first
            this.height = videoMeta?.height ?: dimensions?.second
            this.durationMs = videoMeta?.durationMs
            this.thumbnailUrl = thumbnailUrl
            this.sortOrder = sortOrder
        }
    }
}
