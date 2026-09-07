package com.ninezero.features.coupon

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.util.calcDaysRemaining
import com.ninezero.core.common.util.decodeToTargetIds
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.database.entities.coupon.CouponDao
import com.ninezero.core.database.entities.coupon.UserCouponDao
import com.ninezero.features.coupon.presentation.models.response.CouponResponse
import com.ninezero.features.coupon.presentation.models.response.UserCouponResponse
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom

fun CouponDao.toCouponResponse(): CouponResponse {
    return CouponResponse(
        id = this.id.value,
        creatorId = this.creatorId,
        creatorName = null,
        code = this.code,
        name = this.name,
        description = this.description,
        type = this.type,
        discountTarget = this.discountTarget,
        discountValue = this.discountValue.toAmountString(),
        minOrderAmount = this.minOrderAmount.toAmountString(),
        maxDiscountAmount = this.maxDiscountAmount?.toAmountString(),
        totalQuantity = this.totalQuantity,
        issuedQuantity = this.issuedQuantity,
        remainingQuantity = this.totalQuantity - this.issuedQuantity,
        maxUseCount = this.maxUseCount,
        targetIds = this.targetIds.decodeToTargetIds(),
        status = this.status,
        startDate = this.startDate,
        endDate = this.endDate,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )
}

fun CouponDao.toCouponResponse(creatorName: String?): CouponResponse {
    return this.toCouponResponse().copy(creatorName = creatorName)
}

fun UserCouponDao.toUserCouponResponse(coupon: CouponDao, creatorName: String? = null): UserCouponResponse {
    val now = nowUtc()
    val daysRemaining = calcDaysRemaining(this.expiresAt, now)

    return UserCouponResponse(
        id = this.id.value,
        coupon = coupon.toCouponResponse(creatorName),
        status = this.status,
        useCount = this.useCount,
        maxUseCount = coupon.maxUseCount,
        remainingUseCount = coupon.maxUseCount - this.useCount,
        usedAt = this.usedAt,
        orderId = this.orderId,
        claimedAt = this.claimedAt,
        expiresAt = this.expiresAt,
        daysRemaining = daysRemaining
    )
}

private val couponCodeRandom = SecureRandom()

fun generateCouponCode(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return (1..Constants.Coupon.CODE_LENGTH)
        .map { chars[couponCodeRandom.nextInt(chars.length)] }
        .joinToString("")
}

fun calcCouponDiscount(
    orderAmount: BigDecimal,
    couponType: CouponType,
    discountValue: BigDecimal,
    maxDiscountAmount: BigDecimal?,
    shippingFee: BigDecimal = BigDecimal.ZERO
): BigDecimal {
    val discount = when (couponType) {
        CouponType.PERCENTAGE -> {
            orderAmount.multiply(discountValue.divide(BigDecimal(100)))
                .setScale(0, RoundingMode.HALF_UP)
        }
        CouponType.FIXED_AMOUNT -> {
            discountValue
        }
        CouponType.FREE_SHIPPING -> {
            shippingFee
        }
    }

    val capped = if (maxDiscountAmount != null && discount > maxDiscountAmount) {
        maxDiscountAmount
    } else {
        discount
    }

    // 상품 할인은 크리에이터 소계 초과 불가
    return if (couponType == CouponType.FREE_SHIPPING) {
        capped
    } else {
        capped.coerceAtMost(orderAmount)
    }
}
