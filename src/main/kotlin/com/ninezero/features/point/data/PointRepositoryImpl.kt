package com.ninezero.features.point.data

import com.ninezero.core.database.entities.point.PointDao
import com.ninezero.core.database.entities.point.PointTable
import java.math.BigDecimal

class PointRepositoryImpl : PointRepository {

    /** 포인트 계정 조회 */
    override suspend fun findByUserId(userId: Int): PointDao? {
        return PointDao.find { PointTable.userId eq userId }.singleOrNull()
    }

    /** 포인트 계정 생성 */
    override suspend fun createPointAccount(userId: Int): PointDao {
        return PointDao.new {
            this.userId = userId
            this.balance = BigDecimal.ZERO
            this.totalEarned = BigDecimal.ZERO
            this.totalUsed = BigDecimal.ZERO
            this.totalExpired = BigDecimal.ZERO
        }
    }

    /** 포인트 잔액 교체 */
    override suspend fun updateBalance(userId: Int, newBalance: BigDecimal): PointDao? {
        return PointDao.find { PointTable.userId eq userId }.singleOrNull()?.apply {
            balance = newBalance
        }
    }

    /** 누적 적립액 가산 */
    override suspend fun incrementEarned(userId: Int, amount: BigDecimal) {
        PointDao.find { PointTable.userId eq userId }.singleOrNull()?.apply {
            totalEarned = totalEarned.add(amount)
        }
    }

    /** 누적 사용액 가산 */
    override suspend fun incrementUsed(userId: Int, amount: BigDecimal) {
        PointDao.find { PointTable.userId eq userId }.singleOrNull()?.apply {
            totalUsed = totalUsed.add(amount)
        }
    }

    /** 누적 만료액 가산 */
    override suspend fun incrementExpired(userId: Int, amount: BigDecimal) {
        PointDao.find { PointTable.userId eq userId }.singleOrNull()?.apply {
            totalExpired = totalExpired.add(amount)
        }
    }
}
