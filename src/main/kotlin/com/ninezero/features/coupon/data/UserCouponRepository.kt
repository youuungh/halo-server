package com.ninezero.features.coupon.data

import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.database.entities.coupon.UserCouponDao
import kotlinx.datetime.LocalDateTime

interface UserCouponRepository {

    // 사용자 쿠폰 발급/수정/삭제
    suspend fun create(
        userId: Int,
        couponId: Int,
        expiresAt: LocalDateTime
    ): UserCouponDao

    suspend fun markAsUsed(userCouponId: Int, orderId: Int): Boolean
    suspend fun incrementUseCount(userCouponId: Int): Boolean
    suspend fun refund(userCouponId: Int): Boolean
    suspend fun expireUserCoupons(now: LocalDateTime): Int

    // 탈퇴 정리
    suspend fun deleteAllByUser(userId: Int): Int

    // 사용자 쿠폰 조회
    suspend fun findById(userCouponId: Int): UserCouponDao?
    suspend fun findByUserIdAndCouponId(userId: Int, couponId: Int): UserCouponDao?
    suspend fun findByUserId(userId: Int, page: Int, limit: Int): List<UserCouponDao>
    suspend fun findAvailableByUserId(userId: Int, page: Int, limit: Int): List<UserCouponDao>
    suspend fun findByUserIdAndStatus(userId: Int, status: UserCouponStatus, page: Int, limit: Int): List<UserCouponDao>

    // 카운트
    suspend fun countByUserId(userId: Int): Long
    suspend fun countAvailableByUserId(userId: Int): Long
    suspend fun countByUserIdAndStatus(userId: Int, status: UserCouponStatus): Long

    // 유효성 검사
    suspend fun existsByUserIdAndCouponId(userId: Int, couponId: Int): Boolean
}
