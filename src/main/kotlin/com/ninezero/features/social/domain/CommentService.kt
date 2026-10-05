package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.config.CommentSortType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.LikeType
import com.ninezero.core.common.config.MediaDraftTargetType
import com.ninezero.core.common.config.MediaType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostStatus
import com.ninezero.core.common.config.UserCommentSortType
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.CommentMediaDao
import com.ninezero.core.database.entities.social.CommentMediaTable
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.batchLoadCommentMediaItems
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.social.data.BookmarkRepository
import com.ninezero.features.social.data.CommentRepository
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.LikeRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.extractMentions
import com.ninezero.features.social.presentation.models.response.*
import com.ninezero.features.social.resolveMediaEditPlan
import com.ninezero.features.social.toCommentResponse
import com.ninezero.features.social.toMediaItemResponse
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.upload.domain.validateImageParts
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toUserPreviewResponse
import com.ninezero.features.user.toUserSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SortOrder

class CommentService(
    private val commentRepository: CommentRepository,
    private val postRepository: PostRepository,
    private val likeRepository: LikeRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val userRepository: UserRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val followRepository: FollowRepository,
    private val notificationService: NotificationService,
    private val postService: PostService,
    private val mediaDraftService: MediaDraftService,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val videoProcessingService: VideoProcessingService,
    private val coroutineScope: CoroutineScope,
    private val cacheService: CacheService
) {
    private val logger = logger()

    // 댓글 작성
    suspend fun createComment(
        userId: Int,
        postId: Int,
        content: String,
        parentCommentId: Int? = null,
        mediaDraftIds: List<Int> = emptyList(),
        clientRequestId: String? = null
    ): CommentResponse {
        val trimmedContent = content.trim()

        ValidationUtils.validateCommentContent(trimmedContent)
        ValidationUtils.validateClientRequestId(clientRequestId)

        // 같은 요청이 반복되면 만든 댓글을 그대로
        clientRequestId?.let { requestId ->
            query {
                commentRepository.findCommentByClientRequestId(userId, requestId)?.let { existing ->
                    val author = userRepository.findUserById(userId)?.toUserSummaryResponse()
                        ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)
                    val mediaItems = batchLoadCommentMediaItems(listOf(existing.id.value))[existing.id.value] ?: emptyList()
                    existing.toCommentResponse(author = author, mediaItems = mediaItems)
                }
            }?.let { return it }
        }

        val (response, parentCommentOwnerId, postOwnerId, media) = query {
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

            val createdComment = commentRepository.createComment(
                userId = userId,
                postId = postId,
                content = trimmedContent,
                parentCommentId = parentCommentId,
                clientRequestId = clientRequestId
            )

            postRepository.incrementCommentCount(postId)

            if (parentCommentId != null) {
                commentRepository.incrementRepliesCount(parentCommentId)
            }

            // 초안을 미디어 행으로 옮기고 초안은 지움
            val media = mediaDraftService.claimInTransaction(userId, MediaDraftTargetType.COMMENT, mediaDraftIds)
                .map { it.toPreparedMedia() }
            val mediaItems = media.mapIndexed { index, item ->
                createCommentMediaItem(createdComment.id.value, item, sortOrder = index).toMediaItemResponse()
            }

            val author = userRepository.findUserById(userId)?.toUserSummaryResponse()
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)
            CreatedComment(createdComment.toCommentResponse(author = author, mediaItems = mediaItems), parentOwnerId, post.userId, media)
        }
        val commentId = response.id

        launchCommentVideoTranscode(coroutineScope, videoProcessingService, fileUploadService, media)

        // 댓글 수가 담긴 포스트 상세 캐시
        cacheService.deletePattern(CacheKeys.Patterns.post(postId))
        // 내 목록 캐시(탐색·인기·커뮤니티 등)의 좋아요·저장·댓글 수가 옛 값으로 앱 캐시를 덮지 않게
        cacheService.deletePattern(CacheKeys.Patterns.userScoped(userId))

        coroutineScope.launch {  // 커밋 후 알림 발송
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

                val mentions = extractMentions(trimmedContent)
                if (mentions.isNotEmpty()) {
                    // 답글·댓글 알림을 이미 받은 사람은 멘션 알림 중복 제외
                    val alreadyNotifiedId = parentCommentOwnerId ?: postOwnerId
                    val mentionTargets = query {
                        val post = postRepository.findPostById(postId)
                        // 멘션 사용자명 일괄 조회
                        val users = userRepository.findUsersByUsernames(mentions)
                            .filter { it.id.value != userId && it.id.value != alreadyNotifiedId }
                        // 차단 관계는 대상마다가 아니라 한 번에 조회
                        val blockRelatedUserIds = blockedUserRepository.findBlockRelatedUserIds(userId)

                        users.filter { mentionedUser ->
                            mentionedUser.id.value !in blockRelatedUserIds
                        }.map { mentionedUser ->
                            // 글에 접근 못 하는 대상에겐 본문 미리보기 숨김
                            mentionedUser.id.value to (post != null && postService.checkPostAccess(mentionedUser.id.value, post))
                        }
                    }

                    mentionTargets.forEach { (mentionedUserId, canViewContent) ->
                        notificationService.sendMentionNotification(
                            mentionedUserId = mentionedUserId,
                            mentionerId = userId,
                            postId = postId,
                            commentId = commentId,
                            content = trimmedContent,
                            canViewContent = canViewContent
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("댓글 알림 전송 실패: commentId=$commentId, error=${e.message}", e)
            }
        }

        return response
    }

    /** 댓글 조상 체인 조회 */
    suspend fun getCommentThread(commentId: Int, currentUserId: Int? = null): List<CommentResponse> {
        return query {
            val target = commentRepository.findThreadCommentById(commentId)  // 딥링크 직접 진입용
                ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

            if (postService.isSecretPostHiddenFrom(target.postId, currentUserId)) {
                throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
            }

            // target → root로 부모 체인 수집
            val chain = mutableListOf<CommentDao>()
            var current: CommentDao? = target
            var depth = 0
            while (current != null && depth < COMMENT_THREAD_MAX_DEPTH) {
                chain.add(0, current)
                current = current.parentCommentId?.let { commentRepository.findThreadCommentById(it) }
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
                likeRepository.checkMultipleLikes(currentUserId, LikeType.COMMENT, ids)
            } else emptyMap()
            val bookmarkStatusMap = if (currentUserId != null) {
                bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.COMMENT, ids)
            } else emptyMap()
            val mediaItemsMap = batchLoadCommentMediaItems(ids)
            val blockedUserIds = if (currentUserId != null) {
                blockedUserRepository.findBlockRelatedUserIds(currentUserId)
            } else emptySet()

            chain.map { comment ->
                val author = userMap[comment.userId]?.toUserSummaryResponse(isFollowing = followStatusMap[comment.userId])
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
            if (postService.isSecretPostHiddenFrom(postId, currentUserId)) {
                return@query createPagedResponse(emptyList(), PaginationInfo(validPage, validLimit, 0))
            }

            val rootComments = commentRepository.findCommentsByPostId(postId, validPage, validLimit, sort, currentUserId)
            val totalCount = commentRepository.countCommentsByPostId(postId, currentUserId)

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(buildCommentListResponses(rootComments, currentUserId), pagination)
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
            val parentComment = commentRepository.findThreadCommentById(commentId)
            if (parentComment != null && postService.isSecretPostHiddenFrom(parentComment.postId, currentUserId)) {
                return@query createPagedResponse(emptyList(), PaginationInfo(validPage, validLimit, 0))
            }

            val replies = commentRepository.findRepliesByCommentId(commentId, validPage, validLimit, sort, currentUserId)
            val totalCount = commentRepository.countRepliesByCommentId(commentId, currentUserId)

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(buildCommentListResponses(replies, currentUserId), pagination)
        }
    }

    /** 댓글·답글 목록 공통 조립 */
    private suspend fun buildCommentListResponses(
        comments: List<CommentDao>,
        currentUserId: Int?
    ): List<CommentResponse> {
        if (comments.isEmpty()) return emptyList()

        val blockedUserIds = if (currentUserId != null) {
            blockedUserRepository.findBlockRelatedUserIds(currentUserId)
        } else {
            emptySet()
        }

        val commentIds = comments.map { it.id.value }
        val repliesByParentId = commentRepository.findRepliesByParentIds(commentIds)
            .groupBy { it.parentCommentId ?: 0 }
        // 미리보기 답글만 일괄 조회
        val previewRepliesByParentId = repliesByParentId.mapValues { (_, list) -> list.take(REPLY_PREVIEW_LIMIT) }
        val previewReplies = previewRepliesByParentId.values.flatten()

        val allUserIds = (comments + previewReplies).map { it.userId }.distinct()
        val userMap = userRepository.findUsersByIds(allUserIds).associateBy { it.id.value }
        val followStatusMap = if (currentUserId != null) {
            followRepository.checkMultipleFollowStatus(currentUserId, allUserIds)
        } else emptyMap()

        val allCommentIds = commentIds + previewReplies.map { it.id.value }
        val likeStatusMap = if (currentUserId != null) {
            likeRepository.checkMultipleLikes(currentUserId, LikeType.COMMENT, allCommentIds)
        } else {
            emptyMap()
        }

        val bookmarkStatusMap = if (currentUserId != null) {
            bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.COMMENT, allCommentIds)
        } else {
            emptyMap()
        }

        val mediaItemsMap = batchLoadCommentMediaItems(allCommentIds)

        fun authorOf(comment: CommentDao) = userMap[comment.userId]
            ?.toUserSummaryResponse(isFollowing = followStatusMap[comment.userId])
            ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

        return comments.map { comment ->
            // 답글은 미리보기 슬라이스만 렌더
            val replies = previewRepliesByParentId[comment.id.value] ?: emptyList()
            val replyCount = repliesByParentId[comment.id.value]?.size ?: 0

            val firstReply = replies.firstOrNull()?.let { reply ->
                reply.toCommentResponse(
                    author = authorOf(reply),
                    isLiked = likeStatusMap[reply.id.value] ?: false,
                    isBookmarked = bookmarkStatusMap[reply.id.value] ?: false,
                    isBlocked = reply.userId in blockedUserIds,
                    mediaItems = mediaItemsMap[reply.id.value] ?: emptyList()
                )
            }

            val hasMoreReplies = replyCount > 1
            val replyPreviewUsers = if (hasMoreReplies) {
                replies.drop(1).take(REPLY_PREVIEW_LIMIT - 1).mapNotNull { userMap[it.userId]?.toUserPreviewResponse() }
            } else {
                emptyList()
            }

            comment.toCommentResponse(
                author = authorOf(comment),
                isLiked = likeStatusMap[comment.id.value] ?: false,
                isBookmarked = bookmarkStatusMap[comment.id.value] ?: false,
                isBlocked = comment.userId in blockedUserIds,
                firstReply = firstReply,
                hasMoreReplies = hasMoreReplies,
                moreReplyCount = if (hasMoreReplies) replyCount - 1 else 0,
                replyPreviewUsers = replyPreviewUsers,
                mediaItems = mediaItemsMap[comment.id.value] ?: emptyList()
            )
        }
    }

    // 댓글 수정/삭제
    suspend fun updateComment(
        commentId: Int,
        userId: Int,
        content: String
    ): CommentResponse {
        val trimmedContent = content.trim()
        ValidationUtils.validateCommentContent(trimmedContent)

        return query {
            verifyCommentOwnership(  // 작성자만
                commentId = commentId,
                userId = userId,
                errorMessage = Errors.Social.Comment.COMMENT_UPDATE_PERMISSION_DENIED
            )

            val updatedComment = commentRepository.updateComment(commentId, trimmedContent)
                ?: throw CommentUpdateFailedException(Errors.Social.Comment.COMMENT_UPDATE_FAILED)

            val author = userRepository.findUserById(userId)?.toUserSummaryResponse()
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            val isLiked = likeRepository.isLiked(userId, LikeType.COMMENT, updatedComment.id.value)
            val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.COMMENT, updatedComment.id.value)
            val mediaItems = batchLoadCommentMediaItems(listOf(updatedComment.id.value))[updatedComment.id.value] ?: emptyList()

            updatedComment.toCommentResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
        }
    }

    /** 댓글 삭제와 미디어 회수, 답글이 남으면 삭제 자리로 */
    suspend fun deleteComment(commentId: Int, userId: Int) {
        val (postId, mediaAttachments) = query {
            // 작성자에게 보이는 블라인드 댓글도 삭제 대상
            val comment = CommentDao.findById(commentId)?.takeIf { it.isActive || it.isBlinded }
                ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

            if (comment.userId != userId) {  // 작성자만
                throw ForbiddenException(Errors.Social.Comment.COMMENT_DELETE_PERMISSION_DENIED)
            }

            val postId = comment.postId
            val parentId = comment.parentCommentId
            val wasActive = comment.isActive  // 블라인드 댓글은 블라인드 때 개수에서 이미 뺌
            val keepsPlaceholder = commentRepository.hasThreadReplies(commentId)

            comment.isActive = false
            comment.isBlinded = false  // 작성자 화면에서도 사라짐
            comment.isDeleted = keepsPlaceholder

            // 댓글 수는 지운 1개만, 부모 답글 수는 부모 아래 자리가 생기거나 사라질 때만
            if (wasActive) {
                postRepository.decrementCommentCount(postId)
            }
            if (parentId != null && wasActive && !keepsPlaceholder) {
                commentRepository.decrementRepliesCount(parentId)
            }
            if (parentId != null && !wasActive && keepsPlaceholder) {
                commentRepository.incrementRepliesCount(parentId)
            }
            if (!keepsPlaceholder) {
                removeEmptyPlaceholders(parentId)
            }

            // 원본 + 썸네일 수거
            val medias = CommentMediaDao.find { CommentMediaTable.commentId eq commentId }.toList()
            val urls = medias.flatMap { listOfNotNull(it.url, it.thumbnailUrl) }
            medias.forEach { it.delete() }
            postId to urls
        }

        fileUploadService.deleteByUrls(mediaAttachments)

        // 캐시 정리
        cacheService.deletePattern(CacheKeys.Patterns.post(postId))
        cacheService.deletePattern(CacheKeys.Patterns.userScoped(userId))
    }

    // 사용자 댓글 스레드
    suspend fun getUserCommentThreads(
        userId: Int,
        currentUserId: Int?,
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: UserCommentSortType = UserCommentSortType.LATEST,
        contextType: PostContextType? = null
    ): UserCommentThreadsResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            // 블라인드 댓글은 본인 답글 탭에서만 노출
            val viewerId = currentUserId?.takeIf { it == userId }

            // 커서는 비밀글 스레드를 거르기 전 마지막 행 기준
            val (userComments, nextCursor) = commentRepository.findUserComments(
                userId = userId,
                cursor = pageCursor,
                limit = validLimit + 1,
                sort = sort,
                contextType = contextType,
                viewerId = viewerId
            ).toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }

            if (userComments.isEmpty()) {
                return@query CursorResponse(emptyList(), nextCursor = null, hasNext = false)
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

            val allUserIds = (allComments.map { it.userId } + posts.flatMap { listOfNotNull(it.userId, it.creatorId) }).toSet()
            val users = userRepository.findUsersByIds(allUserIds)
            val userMap = users.associateBy { it.id.value }
            val followStatusMap = if (currentUserId != null) {
                followRepository.checkMultipleFollowStatus(currentUserId, allUserIds)
            } else emptyMap()

            val allCommentIds = allComments.map { it.id.value }
            val likeStatusMap = if (currentUserId != null) {
                likeRepository.checkMultipleLikes(currentUserId, LikeType.COMMENT, allCommentIds)
            } else {
                emptyMap()
            }

            val commentBookmarkStatusMap = if (currentUserId != null) {
                bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.COMMENT, allCommentIds)
            } else {
                emptyMap()
            }

            val postLikeStatusMap = if (currentUserId != null) {
                likeRepository.checkMultipleLikes(currentUserId, LikeType.POST, postIds)
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
            val postAccessMap = if (currentUserId != null) {
                postService.checkMultiplePostAccess(currentUserId, posts)
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

            val threads = userComments.mapNotNull { userComment ->
                val post = postMap[userComment.postId] ?: return@mapNotNull null

                val canAccessPost = postAccessMap[post.id.value] ?: false
                // 비밀글 스레드는 비참여자에게 완전 제외
                if (post.isSecret && !canAccessPost) return@mapNotNull null

                val threadCommentsList = mutableListOf<ThreadCommentResponse>()

                userComment.parentCommentId?.let { parentId ->
                    val parent = parentCommentMap[parentId] ?: return@let
                    val parentAuthor = userMap[parent.userId]?.toUserSummaryResponse(isFollowing = followStatusMap[parent.userId])
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

                val userCommentAuthor = userMap[userComment.userId]?.toUserSummaryResponse(isFollowing = followStatusMap[userComment.userId])
                    ?: return@mapNotNull null

                val userCommentReplyTo = userComment.parentCommentId?.let { parentId ->
                    parentCommentMap[parentId]?.let { parent ->
                        userMap[parent.userId]?.toUserSummaryResponse(isFollowing = followStatusMap[parent.userId])
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
                val postAuthor = userMap[post.userId]?.toUserSummaryResponse(isFollowing = followStatusMap[post.userId])
                    ?: return@mapNotNull null

                // 삭제/숨김/잠금이면 excerpt/미디어 제거
                val hideContent = isPostDeleted || isPostHidden || !canAccessPost
                val postSummary = CommentPostSummaryResponse(
                    id = post.id.value,
                    contextType = post.contextType,
                    excerpt = if (hideContent) "" else post.content,
                    mediaItems = if (hideContent) {
                        emptyList()
                    } else {
                        postMediaItemsMap[post.id.value] ?: emptyList()
                    },
                    author = postAuthor,
                    creator = post.creatorId?.let { userMap[it]?.toUserSummaryResponse(isFollowing = followStatusMap[it]) },
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

            CursorResponse(threads, nextCursor, hasNext = nextCursor != null)
        }
    }

    // 댓글 미디어
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
        val mediaAttachmentsToDelete = mutableListOf<String>()
        var prepared: List<PreparedMedia> = emptyList()

        try {
            val finalContent = content?.trim()
            finalContent?.let { ValidationUtils.validateCommentContent(it) }

            // 수정은 사진 파일 파트만
            val files = newFiles.orEmpty()
            val contentTypes = newContentTypes.orEmpty()
            validateImageParts(imageProcessingService, files, contentTypes, StorageConfig.FileSizeLimit.COMMENT)

            val (orderedKeepItemIds, contentToUse, deleteItemIds) = query {
                val comment = verifyCommentOwnership(  // 작성자만
                    commentId = commentId,
                    userId = userId,
                    errorMessage = Errors.Social.Comment.COMMENT_UPDATE_PERMISSION_DENIED
                )

                val currentItems = CommentMediaDao.find { CommentMediaTable.commentId eq commentId }
                    .orderBy(CommentMediaTable.sortOrder to SortOrder.ASC)
                    .toList()

                val plan = resolveMediaEditPlan(currentItems, keepMediaIds, reorder, files.size) { it.sortOrder }

                // 원본 + 썸네일 수거
                mediaAttachmentsToDelete.addAll(
                    plan.deleteItems.flatMap { listOfNotNull(it.url, it.thumbnailUrl) }
                )

                Triple(
                    plan.orderedKeepIds,
                    finalContent ?: comment.content,
                    plan.deleteItems.map { it.id.value }
                )
            }

            prepared = prepareNewMedia(commentId, files)

            val response = query {
                verifyCommentOwnership(
                    commentId = commentId,
                    userId = userId,
                    errorMessage = Errors.Social.Comment.COMMENT_UPDATE_PERMISSION_DENIED
                )

                val updatedComment = commentRepository.updateComment(commentId, contentToUse)
                    ?: throw CommentUpdateFailedException(Errors.Social.Comment.COMMENT_UPDATE_FAILED)

                orderedKeepItemIds.forEachIndexed { index, mediaId ->
                    val media = CommentMediaDao.findById(mediaId)
                        ?: throw InvalidInputException(Errors.Social.Media.KEEP_MEDIA_NOT_FOUND)
                    media.sortOrder = index
                }

                prepared.forEachIndexed { index, media ->
                    createCommentMediaItem(commentId, media, sortOrder = orderedKeepItemIds.size + index)
                }

                deleteItemIds.forEach { mediaId ->
                    CommentMediaDao.findById(mediaId)?.delete()
                }

                val author = userRepository.findUserById(userId)?.toUserSummaryResponse()
                    ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

                val isLiked = likeRepository.isLiked(userId, LikeType.COMMENT, updatedComment.id.value)
                val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.COMMENT, updatedComment.id.value)
                val mediaItems = batchLoadCommentMediaItems(listOf(updatedComment.id.value))[updatedComment.id.value] ?: emptyList()
                updatedComment.toCommentResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
            }

            fileUploadService.deleteByUrls(mediaAttachmentsToDelete)  // 커밋 후 구 파일 삭제

            return response
        } catch (e: Exception) {
            discardPreparedMedia(fileUploadService, prepared)  // 실패 시 신규 업로드분 롤백
            throw e
        }
    }

    /** 새 사진 순서대로 업로드·가공 */
    private suspend fun prepareNewMedia(commentId: Int, files: List<ByteArray>): List<PreparedMedia> {
        val prepared = mutableListOf<PreparedMedia>()
        try {
            files.forEach { prepared += prepareImage(commentId, it) }
        } catch (e: Exception) {
            discardPreparedMedia(fileUploadService, prepared)
            throw e
        }
        return prepared
    }

    private suspend fun prepareImage(commentId: Int, bytes: ByteArray): PreparedMedia {
        // 원본 업로드 뒤 실패 방지로 파생물 먼저 생성
        val dimensions = imageProcessingService.extractImageDimensions(bytes)
        val display = imageProcessingService.generateDisplayImage(bytes)
        val url = fileUploadService.uploadCommentMedia(commentId, bytes, imageProcessingService.imageExtensionForBytes(bytes))
        return PreparedMedia(
            type = MediaType.IMAGE,
            url = url,
            width = dimensions?.first,
            height = dimensions?.second,
            durationMs = null,
            thumbnailUrl = display?.let { runCatching { fileUploadService.uploadCommentThumbnail(commentId, it) }.getOrNull() }
        )
    }

    /** 답글이 모두 사라진 삭제 자리를 위로 따라가며 정리 */
    private suspend fun removeEmptyPlaceholders(parentId: Int?) {
        var current = parentId?.let { CommentDao.findById(it) }
        while (current != null && current.isDeleted && !commentRepository.hasThreadReplies(current.id.value)) {
            current.isDeleted = false
            val grandParentId = current.parentCommentId
            grandParentId?.let { commentRepository.decrementRepliesCount(it) }  // 그 부모 아래 자리 하나가 사라짐
            current = grandParentId?.let { CommentDao.findById(it) }
        }
    }

    private suspend fun verifyCommentOwnership(
        commentId: Int,
        userId: Int,
        errorMessage: String
    ) = commentRepository.findCommentById(commentId)
        ?.also { comment ->
            if (comment.userId != userId) {
                throw ForbiddenException(errorMessage)
            }
        }
        ?: throw CommentNotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

    /** 생성 트랜잭션 결과 */
    private data class CreatedComment(
        val response: CommentResponse,
        val parentCommentOwnerId: Int?,
        val postOwnerId: Int,
        val media: List<PreparedMedia>
    )

    private companion object {
        const val REPLY_PREVIEW_LIMIT = 3
        const val COMMENT_THREAD_MAX_DEPTH = 50
    }
}
