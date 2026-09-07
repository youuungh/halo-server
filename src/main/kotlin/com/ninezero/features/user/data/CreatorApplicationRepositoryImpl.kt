package com.ninezero.features.user.data

import com.ninezero.core.common.config.CreatorApplicationStatus
import com.ninezero.core.common.exception.CreatorApplicationNotFoundException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ValidationException
import com.ninezero.core.common.util.ilike
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.user.CreatorApplicationDao
import com.ninezero.core.database.entities.user.CreatorApplicationTable
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.database.entities.user.UserTable
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class CreatorApplicationRepositoryImpl : CreatorApplicationRepository {

    /** 크리에이터 신청 생성 */
    override suspend fun createApplication(
        userId: Int,
        reason: String,
        portfolioUrl: String?
    ): CreatorApplicationDao {
        return CreatorApplicationDao.new {
            this.userId = userId
            this.reason = reason
            this.portfolioUrl = portfolioUrl
            this.status = CreatorApplicationStatus.PENDING
        }
    }

    /** 유저의 최신 신청 조회 */
    override suspend fun findByUserId(userId: Int): CreatorApplicationDao? {
        return CreatorApplicationDao.find {
            CreatorApplicationTable.userId eq userId
        }
            .orderBy(CreatorApplicationTable.createdAt to SortOrder.DESC, CreatorApplicationTable.id to SortOrder.DESC)  // 최신순
            .firstOrNull()
    }

    /** REJECTED·REVOKED 중 최신 신청 조회 */
    override suspend fun findLastRejectedByUserId(userId: Int): CreatorApplicationDao? {
        return CreatorApplicationDao.find {
            (CreatorApplicationTable.userId eq userId) and
                    ((CreatorApplicationTable.status eq CreatorApplicationStatus.REJECTED) or
                            (CreatorApplicationTable.status eq CreatorApplicationStatus.REVOKED))
        }
            .orderBy(CreatorApplicationTable.reviewedAt to SortOrder.DESC, CreatorApplicationTable.id to SortOrder.DESC)  // reviewedAt 최신순
            .firstOrNull()
    }

    /** status별 신청 목록 조회 */
    override suspend fun findApplicationsByStatus(
        status: CreatorApplicationStatus?,
        page: Int,
        limit: Int,
        search: String?,
        sortBy: String?,
        sortOrder: String?
    ): List<CreatorApplicationDao> {
        val query = CreatorApplicationTable
            .innerJoin(UserTable, { CreatorApplicationTable.userId }, { UserTable.id })
            .innerJoin(UserProfileTable, { UserTable.id }, { UserProfileTable.userId })
            .select(CreatorApplicationTable.columns)

        val conditions = mutableListOf<Op<Boolean>>()

        status?.let {
            conditions.add(CreatorApplicationTable.status eq it)
        }

        search?.let {
            val searchPattern = "%${it}%"
            conditions.add(
                (UserProfileTable.displayName ilike searchPattern) or
                        (UserTable.username ilike searchPattern)
            )
        }

        val finalQuery = if (conditions.isNotEmpty()) {
            query.where { conditions.reduce { acc, op -> acc and op } }
        } else {
            query
        }

        val order = when (sortOrder?.lowercase()) {
            "asc" -> SortOrder.ASC
            "desc" -> SortOrder.DESC
            else -> SortOrder.DESC
        }

        val sortColumn = when (sortBy?.lowercase()) {
            "username" -> UserTable.username
            "status" -> CreatorApplicationTable.status
            "createdat" -> CreatorApplicationTable.createdAt
            "reviewedat" -> CreatorApplicationTable.reviewedAt
            else -> CreatorApplicationTable.createdAt
        }

        return CreatorApplicationDao.wrapRows(
            finalQuery
                .orderBy(sortColumn to order, CreatorApplicationTable.id to SortOrder.DESC)
                .limit(limit)
                .offset(page.toOffset(limit))
        ).toList()
    }

    /** 신청 승인 */
    override suspend fun approveApplication(applicationId: Int, adminId: Int): Int {
        val application = CreatorApplicationDao.findById(applicationId)
            ?: throw CreatorApplicationNotFoundException(applicationId)

        if (application.status != CreatorApplicationStatus.PENDING) {
            throw ValidationException(Errors.User.APPLICATION_ALREADY_PROCESSED)  // 이미 처리된 신청이면 예외
        }

        application.status = CreatorApplicationStatus.APPROVED
        application.reviewedBy = adminId
        application.reviewedAt = nowUtc()

        return application.userId
    }

    /** 신청 거절 */
    override suspend fun rejectApplication(
        applicationId: Int,
        adminId: Int,
        rejectionReason: String
    ): Int {
        val application = CreatorApplicationDao.findById(applicationId)
            ?: throw CreatorApplicationNotFoundException(applicationId)

        if (application.status != CreatorApplicationStatus.PENDING) {
            throw ValidationException(Errors.User.APPLICATION_ALREADY_PROCESSED)  // 이미 처리된 신청이면 예외
        }

        application.status = CreatorApplicationStatus.REJECTED
        application.reviewedBy = adminId
        application.reviewedAt = nowUtc()
        application.rejectionReason = rejectionReason

        return application.userId
    }

    /** 신청 삭제 */
    override suspend fun deleteApplication(id: Int): Boolean {
        val application = CreatorApplicationDao.findById(id) ?: return false
        application.delete()
        return true
    }

    /** status별 신청 수 */
    override suspend fun countByStatus(status: CreatorApplicationStatus?, search: String?): Int {
        // 검색어 없으면 유저 조인 생략
        if (search == null) {
            return if (status != null) {
                CreatorApplicationDao.find { CreatorApplicationTable.status eq status }.count().toInt()
            } else {
                CreatorApplicationDao.all().count().toInt()
            }
        }

        val searchPattern = "%${search}%"

        return CreatorApplicationTable
            .innerJoin(UserTable, { CreatorApplicationTable.userId }, { UserTable.id })
            .innerJoin(UserProfileTable, { UserTable.id }, { UserProfileTable.userId })
            .select(CreatorApplicationTable.id.count())
            .where {
                val searchCondition = (UserProfileTable.displayName ilike searchPattern) or
                        (UserTable.username ilike searchPattern)

                if (status != null) {
                    (CreatorApplicationTable.status eq status) and searchCondition
                } else {
                    searchCondition
                }
            }
            .first()[CreatorApplicationTable.id.count()]
            .toInt()
    }

    /** 전체 신청 수 */
    override suspend fun countAllApplications(): Int {
        return CreatorApplicationDao.all().count().toInt()
    }

    /** 생성 시각 구간의 신청 수 */
    override suspend fun countApplicationsByDateRange(startDate: LocalDateTime, endDate: LocalDateTime): Int {
        return CreatorApplicationDao.find {
            (CreatorApplicationTable.createdAt greaterEq startDate) and
                    (CreatorApplicationTable.createdAt lessEq endDate)  // 양끝 포함
        }.count().toInt()
    }

    /** 평균 승인 소요일 */
    override suspend fun avgApprovalTime(): Double {
        val durations = CreatorApplicationTable
            .select(CreatorApplicationTable.createdAt, CreatorApplicationTable.reviewedAt)
            .where {
                (CreatorApplicationTable.status eq CreatorApplicationStatus.APPROVED) and
                        (CreatorApplicationTable.reviewedAt.isNotNull())
            }
            .map { row ->
                val createdAt = row[CreatorApplicationTable.createdAt]
                val reviewedAt = row[CreatorApplicationTable.reviewedAt]!!

                val duration = reviewedAt.toInstant(TimeZone.UTC)
                    .minus(createdAt.toInstant(TimeZone.UTC))
                duration.inWholeDays.toDouble()
            }

        return if (durations.isEmpty()) 0.0 else durations.average()
    }
}
