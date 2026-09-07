package com.ninezero.features.social.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.ReportTargetType
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.CommentMediaDao
import com.ninezero.core.database.entities.social.CommentMediaTable
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.features.social.data.ReportRepository
import com.ninezero.features.social.presentation.models.response.ReportedTargetDetailResponse
import com.ninezero.features.social.toMediaItemResponse
import com.ninezero.features.social.presentation.models.response.ReportedTargetListResponse
import com.ninezero.features.social.presentation.models.response.ReportedTargetResponse
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse

data class ReportResult(
    val reportCount: Int,
    val blinded: Boolean
)

class ReportService(
    private val reportRepository: ReportRepository,
    private val userRepository: UserRepository
) {

    suspend fun report(
        reporterId: Int,
        targetType: ReportTargetType,
        targetId: Int
    ): ReportResult = query {
        validateTargetExistsAndNotOwn(reporterId, targetType, targetId)

        reportRepository.createReport(reporterId, targetType, targetId)
            ?: throw ConflictException(alreadyReportedMessage(targetType))  // 중복 신고 시 409

        val reportCount = reportRepository.countReports(targetType, targetId)
        val blinded = applyAutoBlindIfNeeded(targetType, targetId, reportCount)  // 임계치 도달 시 자동 블라인드

        ReportResult(reportCount = reportCount, blinded = blinded)
    }

    suspend fun findReportedTargets(
        targetType: ReportTargetType?,
        page: Int,
        limit: Int
    ): ReportedTargetListResponse = query {
        val rows = reportRepository.findReportedTargets(targetType, page, limit)
        val total = reportRepository.countReportedTargets(targetType)

        val items = rows.map { row ->
            val target = when (row.targetType) {  // 포스트/댓글은 최신 상태로 갱신
                ReportTargetType.POST -> PostDao.findById(row.targetId)
                    ?.let { row.copy(isBlinded = it.isBlinded, moderationLocked = it.moderationLocked) } ?: row

                ReportTargetType.COMMENT -> CommentDao.findById(row.targetId)
                    ?.let { row.copy(isBlinded = it.isBlinded, moderationLocked = it.moderationLocked) } ?: row

                ReportTargetType.USER -> row
            }

            ReportedTargetResponse(
                targetType = target.targetType,
                targetId = target.targetId,
                reportCount = target.reportCount,
                isBlinded = target.isBlinded,
                moderationLocked = target.moderationLocked
            )
        }

        createPagedResponse(items, PaginationInfo(page, limit, total))
    }

    /** 관리자 전용 대상 상세 */
    suspend fun findReportedTargetDetail(
        targetType: ReportTargetType,
        targetId: Int
    ): ReportedTargetDetailResponse = query {
        val reportCount = reportRepository.countReports(targetType, targetId)

        when (targetType) {
            ReportTargetType.POST -> {
                val post = PostDao.findById(targetId)
                    ?: throw NotFoundException(Errors.Social.Post.POST_NOT_FOUND)

                ReportedTargetDetailResponse(
                    targetType = targetType,
                    targetId = targetId,
                    reportCount = reportCount,
                    isBlinded = post.isBlinded,
                    moderationLocked = post.moderationLocked,
                    author = userRepository.findUserById(post.userId)?.toSummaryResponse(),
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
                    author = userRepository.findUserById(comment.userId)?.toSummaryResponse(),
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
                    author = user.toSummaryResponse(),
                    createdAt = user.createdAt
                )
            }
        }
    }

    /** 관리자 수동 블라인드 */
    suspend fun blind(targetType: ReportTargetType, targetId: Int): Unit = query {
        when (targetType) {
            ReportTargetType.POST -> {
                val post = PostDao.findById(targetId) ?: throw NotFoundException(Errors.Social.Post.POST_NOT_FOUND)
                post.isActive = false
                post.isBlinded = true
            }

            ReportTargetType.COMMENT -> {
                val comment = CommentDao.findById(targetId)
                    ?: throw NotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
                comment.isActive = false
                comment.isBlinded = true
            }

            ReportTargetType.USER -> throw InvalidInputException(Errors.Social.Report.USER_CANNOT_BE_BLINDED)  // USER는 블라인드 불가
        }
    }

    /** 관리자 수동 블라인드 해제 */
    suspend fun unblind(targetType: ReportTargetType, targetId: Int): Unit = query {
        when (targetType) {
            ReportTargetType.POST -> {
                val post = PostDao.findById(targetId) ?: throw NotFoundException(Errors.Social.Post.POST_NOT_FOUND)
                if (!post.isBlinded) throw InvalidInputException(Errors.Social.Report.NOT_BLINDED)
                post.isActive = true
                post.isBlinded = false
                post.moderationLocked = true  // 재블라인드 차단 마커
            }

            ReportTargetType.COMMENT -> {
                val comment = CommentDao.findById(targetId)
                    ?: throw NotFoundException(Errors.Social.Comment.COMMENT_NOT_FOUND)
                if (!comment.isBlinded) throw InvalidInputException(Errors.Social.Report.NOT_BLINDED)
                comment.isActive = true
                comment.isBlinded = false
                comment.moderationLocked = true  // 재블라인드 차단 마커
            }

            ReportTargetType.USER -> throw InvalidInputException(Errors.Social.Report.USER_CANNOT_BE_BLINDED)
        }
    }

    private fun applyAutoBlindIfNeeded(
        targetType: ReportTargetType,
        targetId: Int,
        reportCount: Int
    ): Boolean {
        return reportCount >= Constants.Social.AUTO_BLIND_REPORT_THRESHOLD && when (targetType) {
            ReportTargetType.POST -> {
                val post = PostDao.findById(targetId) ?: return false
                if (post.moderationLocked || post.isBlinded || !post.isActive) return false
                post.isActive = false
                post.isBlinded = true
                true
            }

            ReportTargetType.COMMENT -> {
                val comment = CommentDao.findById(targetId) ?: return false
                if (comment.moderationLocked || comment.isBlinded || !comment.isActive) return false
                comment.isActive = false
                comment.isBlinded = true
                true
            }

            ReportTargetType.USER -> false
        }
    }

    private suspend fun validateTargetExistsAndNotOwn(reporterId: Int, targetType: ReportTargetType, targetId: Int) {
        when (targetType) {
            ReportTargetType.POST -> {
                val post = PostDao.findById(targetId)?.takeIf { it.isActive }
                    ?: throw NotFoundException(Errors.Social.Post.POST_NOT_FOUND)
                if (post.userId == reporterId) {
                    throw InvalidInputException(Errors.Social.Report.CANNOT_REPORT_OWN_POST)
                }
            }

            ReportTargetType.COMMENT -> {
                val comment = CommentDao.findById(targetId)?.takeIf { it.isActive }
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
