package com.ninezero.features.coupon.data

import com.ninezero.core.common.config.CouponStatus
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.database.entities.coupon.CouponDao
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

interface CouponRepository {

    // 쿠폰 생성/수정/삭제
    suspend fun create(
        creatorId: Int,
        code: String,
        name: String,
        description: String?,
        type: CouponType,
        discountTarget: CouponDiscountTarget,
        discountValue: BigDecimal,
        minOrderAmount: BigDecimal,
        maxDiscountAmount: BigDecimal?,
        totalQuantity: Int,
        maxUseCount: Int,
        targetIds: String?,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): CouponDao

    suspend fun updateCoupon(
        couponId: Int,
        name: String?,
        description: String?,
        minOrderAmount: BigDecimal?,
        maxDiscountAmount: BigDecimal?,
        totalQuantity: Int?,
        maxUseCount: Int?,
        status: CouponStatus?,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?
    ): Boolean

    suspend fun deleteCoupon(couponId: Int): Boolean

    suspend fun disableActiveCouponsByCreator(creatorId: Int): Int

    // 쿠폰 조회
    suspend fun findById(couponId: Int): CouponDao?
    suspend fun findByIds(couponIds: List<Int>): List<CouponDao>
    suspend fun findByCode(code: String): CouponDao?
    suspend fun findByCreatorId(creatorId: Int, page: Int, limit: Int): List<CouponDao>
    suspend fun findActive(page: Int, limit: Int): List<CouponDao>

    // 쿠폰 발급
    suspend fun claimByCode(userId: Int, code: String): Pair<CouponDao, Int>

    // 카운트
    suspend fun countByCreatorId(creatorId: Int): Long
    suspend fun countActive(): Long

    // 유효성 검사
    suspend fun existsByCode(code: String): Boolean
}
