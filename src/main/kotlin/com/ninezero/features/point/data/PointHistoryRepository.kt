package com.ninezero.features.point.data

import com.ninezero.core.common.config.PointType
import com.ninezero.core.database.entities.point.PointHistoryDao
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

interface PointHistoryRepository {

    // 포인트 내역 생성
    suspend fun create(
        userId: Int,
        type: PointType,
        amount: BigDecimal,
        balanceBefore: BigDecimal,
        balanceAfter: BigDecimal,
        orderId: Int? = null,
        description: String,
        expiresAt: LocalDateTime? = null
    ): PointHistoryDao

    // 포인트 내역 조회
    suspend fun findByUserId(
        userId: Int, page: Int, limit: Int,
        startDate: LocalDateTime? = null, endDate: LocalDateTime? = null,
        type: PointType? = null
    ): List<PointHistoryDao>

    suspend fun findByOrderId(orderId: Int): List<PointHistoryDao>
    suspend fun findExpiredPoints(now: LocalDateTime, limit: Int): List<PointHistoryDao>
    suspend fun findNextExpiring(userId: Int, now: LocalDateTime): PointHistoryDao?

    // 카운트
    suspend fun countByUserId(
        userId: Int,
        startDate: LocalDateTime? = null,
        endDate: LocalDateTime? = null,
        type: PointType? = null
    ): Long

    // 탈퇴 정리
    suspend fun deleteAllByUser(userId: Int): Int
}
