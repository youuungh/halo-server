package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.ReportTargetType
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.CommentMediaDao
import com.ninezero.core.database.entities.social.CommentMediaTable
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.features.social.communityCreatorId
import com.ninezero.features.social.data.CommentRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.data.ReportRepository
import com.ninezero.features.social.invalidatePostCaches
import com.ninezero.features.social.presentation.models.response.ReportResponse
import com.ninezero.features.social.presentation.models.response.ReportedTargetDetailResponse
import com.ninezero.features.social.presentation.models.response.ReportedTargetListResponse
import com.ninezero.features.social.presentation.models.response.ReportedTargetResponse
import com.ninezero.features.social.toMediaItemResponse
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toUserSummaryResponse

/** 블라인드로 캐시를 지울 포스트 */
private data class AffectedPost(
    val postId: Int,
    val authorId: Int,
    val communityCreatorId: Int?
)

class ReportService(
    private val reportRepository: ReportRepository,
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val cacheService: CacheService
) {

    suspend fun report(
        reporterId: Int,
        targetType: ReportTargetType,
        targetId: Int
    ): ReportResponse {
        val (result, affected) = query {
            validateTargetExistsAndNotOwn(reporterId, targetType, targetId)

            reportRepository.createReport(reporterId, targetType, targetId)
                ?: throw ConflictException(alreadyReportedMessage(targetType))  // 중복 신고 시 409

            val reportCount = reportRepository.countReports(targetType, targetId)
            val blinded = applyAutoBlindIfNeeded(targetType, targetId, reportCount)  // 임계치 도달 시 자동 블라인드

            ReportResponse(reportCount = reportCount, blinded = blinded) to
                (if (blinded) affectedPostOf(targetType, targetId) else null)
        }

        affected?.let { invalidateCaches(it) }
        return result
    }

    suspend fun findReportedTargets(
        targetType: ReportTargetType?,
        page: Int,
        limit: Int
    ): ReportedTargetListResponse = query {
        val (validPage, validLimit) = validatePaginationParams(page, limit)
        val rows = reportRepository.findReportedTargets(targetType, validPage, validLimit)
        val total = reportRepository.countReportedTargets(targetType)

        // 포스트/댓글은 최신 상태로 갱신, 타입별로 한 번에 조회
        val postStates = postRepository
            .findPostsByIdsWithDeleted(rows.filter { it.targetType == ReportTargetType.POST }.map { it.targetId })
            .associate { it.id.value to (it.isBlinded to it.moderationLocked) }
        val commentStates = commentRepository
            .findCommentsByIdsWithDeleted(rows.filter { it.targetType == ReportTargetType.COMMENT }.map { it.targetId })
            .associate { it.id.value to (it.isBlinded to it.moderationLocked) }

        val items = rows.map { row ->
            val (isBlinded, moderationLocked) = when (row.targetType) {
                ReportTargetType.POST -> postStates[row.targetId]
                ReportTargetType.COMMENT -> commentStates[row.targetId]
                ReportTargetType.USER -> null
            } ?: (false to false)

            ReportedTargetResponse(
                targetType = row.targetType,
                targetId = row.targetId,
                reportCount = row.reportCount,
                isBlinded = isBlinded,
                moderationLocked = moderationLocked
            )
        }

        createPagedResponse(items, PaginationInfo(validPage, validLimit, total))
    }

    /** 관리자 전용 대상 상세 */
    suspend fun findReportedTargetDetail(
        targetType: ReportTargetType,
        targetId: Int
    ): ReportedTargetDetailResponse = query {
        val reportCount = reportRepository.countReports(targetType, targetId)

        when (targetType) {
            ReportTargetType.POST -> {
                val post = postRepository.findPostByIdWithDeleted(targetId)
                    ?: throw NotFoundException(Errors.Social.Post.POST_NOT_FOUND)

                ReportedTargetDetailResponse(
                    targetType = targetType,
                    targetId = targetId,
                    reportCount = reportCount,
                    isBlinded = post.isBlinded,
                    moderationLocked = post.moderationLocked,
                    author = userRepository.findUserById(post.userId)?.toUserSummaryResponse(),
                    content = post.content,
                    mediaItems = PostMediaDao
                        .find { PostMediaTable.postId eq targetId }
                        .sortedBy { it.sortOrder }
                        .map { it.toMediaItemResponse() },
                    likeCount = post.likeCount,
                    commentCount = post.commentCount,
                    createdAt = post.createdAt
                )
            }

            ReportTargetType.COMMENT -> {
                val comment = CommentDao.findById(targetId)
                    ?: throw NotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)

                ReportedTargetDetailResponse(
                    targetType = targetType,
                    targetId = targetId,
                    reportCount = reportCount,
                    isBlinded = comment.isBlinded,
                    moderationLocked = comment.moderationLocked,
                    author = userRepository.findUserById(comment.userId)?.toUserSummaryResponse(),
                    content = comment.content,
                    mediaItems = CommentMediaDao
                        .find { CommentMediaTable.commentId eq targetId }
                        .sortedBy { it.sortOrder }
                        .map { it.toMediaItemResponse() },
                    likeCount = comment.likeCount,
                    createdAt = comment.createdAt
                )
            }

            ReportTargetType.USER -> {
                val user = userRepository.findUserById(targetId)
                    ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

                ReportedTargetDetailResponse(
                    targetType = targetType,
                    targetId = targetId,
                    reportCount = reportCount,
                    isBlinded = false,
                    moderationLocked = false,
                    author = user.toUserSummaryResponse(),
                    createdAt = user.createdAt
                )
            }
        }
    }

    /** 관리자 수동 블라인드 */
    suspend fun blind(targetType: ReportTargetType, targetId: Int) {
        val affected = query {
            when (targetType) {
                ReportTargetType.POST -> {
                    // 작성자가 지운 글은 블라인드로 되살리지 않음
                    val post = postRepository.findPostByIdWithDeleted(targetId)?.takeIf { it.isActive || it.isBlinded }
                        ?: throw NotFoundException(Errors.Social.Post.POST_NOT_FOUND)
                    post.isActive = false
                    post.isBlinded = true
                }

                ReportTargetType.COMMENT -> {
                    val comment = CommentDao.findById(targetId)?.takeIf { it.isActive || it.isBlinded }
                        ?: throw NotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
                    if (comment.isActive) blindComment(comment)
                }

                ReportTargetType.USER -> throw InvalidInputException(Errors.Social.Report.USER_CANNOT_BE_BLINDED)  // USER는 블라인드 불가
            }
            affectedPostOf(targetType, targetId)
        }

        affected?.let { invalidateCaches(it) }
    }

    /** 관리자 수동 블라인드 해제 */
    suspend fun unblind(targetType: ReportTargetType, targetId: Int) {
        val affected = query {
            when (targetType) {
                ReportTargetType.POST -> {
                    val post = postRepository.findPostByIdWithDeleted(targetId) ?: throw NotFoundException(Errors.Social.Post.POST_NOT_FOUND)
                    if (!post.isBlinded) throw InvalidInputException(Errors.Social.Report.NOT_BLINDED)
                    post.isActive = true
                    post.isBlinded = false
                    post.moderationLocked = true  // 재블라인드 차단 마커
                }

                ReportTargetType.COMMENT -> {
                    val comment = CommentDao.findById(targetId)
                        ?: throw NotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
                    if (!comment.isBlinded) throw InvalidInputException(Errors.Social.Report.NOT_BLINDED)
                    unblindComment(comment)
                    comment.moderationLocked = true  // 재블라인드 차단 마커
                }

                ReportTargetType.USER -> throw InvalidInputException(Errors.Social.Report.USER_CANNOT_BE_BLINDED)
            }
            affectedPostOf(targetType, targetId)
        }

        affected?.let { invalidateCaches(it) }
    }

    private suspend fun applyAutoBlindIfNeeded(
        targetType: ReportTargetType,
        targetId: Int,
        reportCount: Int
    ): Boolean {
        return reportCount >= Constants.Social.AUTO_BLIND_REPORT_THRESHOLD && when (targetType) {
            ReportTargetType.POST -> {
                val post = postRepository.findPostByIdWithDeleted(targetId) ?: return false
                if (post.moderationLocked || post.isBlinded || !post.isActive) return false
                post.isActive = false
                post.isBlinded = true
                true
            }

            ReportTargetType.COMMENT -> {
                val comment = CommentDao.findById(targetId) ?: return false
                if (comment.moderationLocked || comment.isBlinded || !comment.isActive) return false
                blindComment(comment)
                true
            }

            ReportTargetType.USER -> false
        }
    }

    /** 활성 댓글 블라인드 */
    private suspend fun blindComment(comment: CommentDao) {
        val parentId = comment.parentCommentId
        val keepsPlaceholder = commentRepository.hasThreadReplies(comment.id.value)
        comment.isActive = false
        comment.isBlinded = true
        comment.isDeleted = keepsPlaceholder

        // 포스트 댓글 수는 바로, 부모 답글 수는 자리가 사라질 때만
        postRepository.decrementCommentCount(comment.postId)
        if (parentId != null && !keepsPlaceholder) {
            commentRepository.decrementRepliesCount(parentId)
            commentRepository.removeEmptyPlaceholders(parentId)
        }
    }

    /** 블라인드 해제 */
    private suspend fun unblindComment(comment: CommentDao) {
        val parentId = comment.parentCommentId
        val wasPlaceholder = comment.isDeleted
        comment.isActive = true
        comment.isBlinded = false
        comment.isDeleted = false

        postRepository.incrementCommentCount(comment.postId)
        if (parentId != null && !wasPlaceholder) commentRepository.incrementRepliesCount(parentId)
    }

    /** 블라인드 대상의 소속 포스트 */
    private suspend fun affectedPostOf(targetType: ReportTargetType, targetId: Int): AffectedPost? {
        val post = when (targetType) {
            ReportTargetType.POST -> postRepository.findPostByIdWithDeleted(targetId)
            ReportTargetType.COMMENT -> CommentDao.findById(targetId)?.let { postRepository.findPostByIdWithDeleted(it.postId) }
            ReportTargetType.USER -> null
        } ?: return null

        return AffectedPost(
            postId = post.id.value,
            authorId = post.userId,
            communityCreatorId = post.communityCreatorId
        )
    }

    private suspend fun invalidateCaches(post: AffectedPost) {
        cacheService.invalidatePostCaches(post.postId, post.authorId, post.communityCreatorId)
    }

    private suspend fun validateTargetExistsAndNotOwn(reporterId: Int, targetType: ReportTargetType, targetId: Int) {
        when (targetType) {
            ReportTargetType.POST -> {
                val post = postRepository.findPostById(targetId)
                    ?: throw NotFoundException(Errors.Social.Post.POST_NOT_FOUND)
                if (post.userId == reporterId) {
                    throw InvalidInputException(Errors.Social.Report.CANNOT_REPORT_OWN_POST)
                }
            }

            ReportTargetType.COMMENT -> {
                val comment = commentRepository.findCommentById(targetId)
                    ?: throw NotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
                if (comment.userId == reporterId) {
                    throw InvalidInputException(Errors.Social.Report.CANNOT_REPORT_OWN_COMMENT)
                }
            }

            ReportTargetType.USER -> {
                if (targetId == reporterId) {
                    throw InvalidInputException(Errors.Social.Report.CANNOT_REPORT_SELF)
                }
                userRepository.findUserById(targetId)?.takeIf { it.isActive }
                    ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)
            }
        }
    }

    private fun alreadyReportedMessage(targetType: ReportTargetType) = when (targetType) {
        ReportTargetType.POST -> Errors.Social.Report.ALREADY_REPORTED_POST
        ReportTargetType.COMMENT -> Errors.Social.Report.ALREADY_REPORTED_COMMENT
        ReportTargetType.USER -> Errors.Social.Report.ALREADY_REPORTED_USER
    }
}
