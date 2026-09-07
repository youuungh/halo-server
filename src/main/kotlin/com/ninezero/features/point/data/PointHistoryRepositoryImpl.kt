package com.ninezero.features.point.data

import com.ninezero.core.common.config.PointType
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.point.PointHistoryDao
import com.ninezero.core.database.entities.point.PointHistoryTable
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import java.math.BigDecimal

class PointHistoryRepositoryImpl : PointHistoryRepository {

    /** 포인트 내역 생성 */
    override suspend fun create(
        userId: Int,
        type: PointType,
        amount: BigDecimal,
        balanceBefore: BigDecimal,
        balanceAfter: BigDecimal,
        orderId: Int?,
        description: String,
        expiresAt: LocalDateTime?
    ): PointHistoryDao {
        return PointHistoryDao.new {
            this.userId = userId
            this.type = type
            this.amount = amount
            this.balanceBefore = balanceBefore
            this.balanceAfter = balanceAfter
            this.orderId = orderId
            this.description = description
            this.expiresAt = expiresAt
        }
    }

    /** 탈퇴 정리용 포인트 내역 삭제 */
    override suspend fun deleteAllByUser(userId: Int): Int {
        return PointHistoryTable.deleteWhere { this.userId eq userId }  // 포인트 계정은 유지
    }

    /** 포인트 내역 목록 조회 */
    override suspend fun findByUserId(
        userId: Int, page: Int, limit: Int,
        startDate: LocalDateTime?, endDate: LocalDateTime?,
        type: PointType?
    ): List<PointHistoryDao> {
        return PointHistoryDao.find { buildUserHistoryCondition(userId, startDate, endDate, type) }
            .orderBy(PointHistoryTable.createdAt to SortOrder.DESC, PointHistoryTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 주문에 연결된 포인트 내역 목록 조회 */
    override suspend fun findByOrderId(orderId: Int): List<PointHistoryDao> {
        return PointHistoryDao.find { PointHistoryTable.orderId eq orderId }
            .orderBy(PointHistoryTable.createdAt to SortOrder.DESC, PointHistoryTable.id to SortOrder.DESC)  // 최신순
            .toList()
    }

    /** 가장 임박한 만료 예정 EARN 내역 */
    override suspend fun findNextExpiring(userId: Int, now: LocalDateTime): PointHistoryDao? {
        return PointHistoryDao.find {
            (PointHistoryTable.userId eq userId) and
                    (PointHistoryTable.type eq PointType.EARN) and
                    (PointHistoryTable.expiresAt.isNotNull()) and
                    (PointHistoryTable.expiresAt greater now)
        }
            .orderBy(PointHistoryTable.expiresAt to SortOrder.ASC, PointHistoryTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
    }

    /** 만료된 EARN 내역 조회 */
    override suspend fun findExpiredPoints(now: LocalDateTime, limit: Int): List<PointHistoryDao> {
        return PointHistoryDao.find {
            (PointHistoryTable.type eq PointType.EARN) and
                    (PointHistoryTable.expiresAt lessEq now)
        }
            .orderBy(PointHistoryTable.expiresAt to SortOrder.ASC, PointHistoryTable.id to SortOrder.DESC)
            .limit(limit)
            .toList()
    }

    /** 포인트 내역 수 */
    override suspend fun countByUserId(
        userId: Int,
        startDate: LocalDateTime?, endDate: LocalDateTime?,
        type: PointType?
    ): Long {
        return PointHistoryDao.find { buildUserHistoryCondition(userId, startDate, endDate, type) }.count()
    }

    private fun buildUserHistoryCondition(
        userId: Int,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?,
        type: PointType?
    ): Op<Boolean> = Op.build {
        var condition: Op<Boolean> = PointHistoryTable.userId eq userId
        startDate?.let { condition = condition and (PointHistoryTable.createdAt greaterEq it) }
        endDate?.let { condition = condition and (PointHistoryTable.createdAt lessEq it) }
        type?.let { condition = condition and (PointHistoryTable.type eq it) }
        condition
    }
}
