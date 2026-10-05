package com.ninezero.features.social.data

import com.ninezero.core.common.config.ReportTargetType
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.social.ReportDao
import com.ninezero.core.database.entities.social.ReportTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count

class ReportRepositoryImpl : ReportRepository {

    /** 신고 생성 */
    override suspend fun createReport(
        reporterId: Int,
        targetType: ReportTargetType,
        targetId: Int
    ): ReportDao? {
        if (hasReported(reporterId, targetType, targetId)) return null  // 이미 신고했으면 null

        return ReportDao.new {
            this.reporterId = reporterId
            this.targetType = targetType
            this.targetId = targetId
        }
    }

    /** 신고 여부 확인 */
    private fun hasReported(
        reporterId: Int,
        targetType: ReportTargetType,
        targetId: Int
    ): Boolean = !ReportDao.find {
        (ReportTable.reporterId eq reporterId) and
                (ReportTable.targetType eq targetType) and
                (ReportTable.targetId eq targetId)
    }.empty()

    /** 신고된 대상 목록 */
    override suspend fun findReportedTargets(
        targetType: ReportTargetType?,
        page: Int,
        limit: Int
    ): List<ReportedTargetRow> {
        val countCol = ReportTable.id.count()
        return ReportTable
            .select(ReportTable.targetType, ReportTable.targetId, countCol)
            .apply { targetType?.let { where { ReportTable.targetType eq it } } }  // null이면 전체
            .groupBy(ReportTable.targetType, ReportTable.targetId)
            // 신고 많은 순, 동수는 targetId 보조정렬
            .orderBy(countCol to SortOrder.DESC, ReportTable.targetId to SortOrder.DESC)
            .limit(limit).offset(page.toOffset(limit))
            .map {
                ReportedTargetRow(
                    targetType = it[ReportTable.targetType],
                    targetId = it[ReportTable.targetId],
                    reportCount = it[countCol].toInt()
                )
            }
    }

    /** 대상의 신고 수 */
    override suspend fun countReports(targetType: ReportTargetType, targetId: Int): Int =
        ReportDao.find {
            (ReportTable.targetType eq targetType) and (ReportTable.targetId eq targetId)
        }.count().toInt()

    /** 신고된 대상 수 */
    override suspend fun countReportedTargets(targetType: ReportTargetType?): Int =
        ReportTable
            .select(ReportTable.targetType, ReportTable.targetId)
            .apply { targetType?.let { where { ReportTable.targetType eq it } } }
            .groupBy(ReportTable.targetType, ReportTable.targetId)  // 신고 수가 아닌 대상 수
            .count().toInt()
}
