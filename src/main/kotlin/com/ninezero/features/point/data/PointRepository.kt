package com.ninezero.features.point.data

import com.ninezero.core.database.entities.point.PointDao
import java.math.BigDecimal

interface PointRepository {

    // 포인트 계정 조회
    suspend fun findByUserId(userId: Int): PointDao?

    // 포인트 계정 생성
    suspend fun createPointAccount(userId: Int): PointDao

    // 포인트 잔액/누적 수정
    suspend fun updateBalance(userId: Int, newBalance: BigDecimal): PointDao?
    suspend fun incrementEarned(userId: Int, amount: BigDecimal)
    suspend fun incrementUsed(userId: Int, amount: BigDecimal)
    suspend fun incrementExpired(userId: Int, amount: BigDecimal)
}
