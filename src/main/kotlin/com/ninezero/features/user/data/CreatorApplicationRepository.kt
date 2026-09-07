package com.ninezero.features.user.data

import com.ninezero.core.common.config.CreatorApplicationStatus
import com.ninezero.core.database.entities.user.CreatorApplicationDao
import kotlinx.datetime.LocalDateTime

interface CreatorApplicationRepository {

    // 신청 생성
    suspend fun createApplication(
        userId: Int,
        reason: String,
        portfolioUrl: String?
    ): CreatorApplicationDao

    // 신청 조회
    suspend fun findByUserId(userId: Int): CreatorApplicationDao?
    suspend fun findLastRejectedByUserId(userId: Int): CreatorApplicationDao?

    suspend fun findApplicationsByStatus(
        status: CreatorApplicationStatus? = null,
        page: Int,
        limit: Int,
        search: String? = null,
        sortBy: String? = null,
        sortOrder: String? = null
    ): List<CreatorApplicationDao>

    // 신청 승인/거절
    suspend fun approveApplication(applicationId: Int, adminId: Int): Int
    suspend fun rejectApplication(applicationId: Int, adminId: Int, rejectionReason: String): Int

    // 신청 삭제
    suspend fun deleteApplication(id: Int): Boolean

    // 카운트
    suspend fun countByStatus(status: CreatorApplicationStatus? = null, search: String? = null): Int

    // 관리자 통계
    suspend fun countAllApplications(): Int
    suspend fun countApplicationsByDateRange(startDate: LocalDateTime, endDate: LocalDateTime): Int
    suspend fun avgApprovalTime(): Double
}
