package com.ninezero.features.social.data

import com.ninezero.core.common.config.ReportTargetType
import com.ninezero.core.database.entities.social.ReportDao

data class ReportedTargetRow(
    val targetType: ReportTargetType,
    val targetId: Int,
    val reportCount: Int,
    val isBlinded: Boolean = false,
    val moderationLocked: Boolean = false
)

interface ReportRepository {

    // 신고 생성
    suspend fun createReport(
        reporterId: Int,
        targetType: ReportTargetType,
        targetId: Int
    ): ReportDao?

    // 신고 조회
    suspend fun hasReported(reporterId: Int, targetType: ReportTargetType, targetId: Int): Boolean
    suspend fun findReportedTargets(
        targetType: ReportTargetType?,
        page: Int,
        limit: Int
    ): List<ReportedTargetRow>

    // 카운트
    suspend fun countReports(targetType: ReportTargetType, targetId: Int): Int
    suspend fun countReportsByTargets(targetType: ReportTargetType, targetIds: List<Int>): Map<Int, Int>
    suspend fun countReportedTargets(targetType: ReportTargetType?): Int
}
