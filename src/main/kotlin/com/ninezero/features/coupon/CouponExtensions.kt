package com.ninezero.features.coupon

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.util.calcDaysRemaining
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.database.entities.coupon.CouponDao
import com.ninezero.core.database.entities.coupon.UserCouponDao
import com.ninezero.features.coupon.presentation.models.response.CouponResponse
import com.ninezero.features.coupon.presentation.models.response.UserCouponResponse
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom

fun CouponDao.toCouponResponse(creatorName: String?): CouponResponse {
    return CouponResponse(
        id = id.value,
        creatorId = creatorId,
        creatorName = creatorName,
        code = code,
        name = name,
        description = description,
        type = type,
        discountTarget = discountTarget,
        discountValue = this.discountValue.toAmountString(),
        minOrderAmount = minOrderAmount.toAmountString(),
        maxDiscountAmount = this.maxDiscountAmount?.toAmountString(),
        totalQuantity = totalQuantity,
        issuedQuantity = issuedQuantity,
        remainingQuantity = totalQuantity - issuedQuantity,
        maxUseCount = maxUseCount,
        targetIds = targetIds.decodeToTargetIds(),
        status = status,
        startDate = startDate,
        endDate = endDate,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun UserCouponDao.toUserCouponResponse(coupon: CouponDao, creatorName: String?): UserCouponResponse {
    val now = nowUtc()
    val daysRemaining = calcDaysRemaining(expiresAt, now)

    return UserCouponResponse(
        id = id.value,
        coupon = coupon.toCouponResponse(creatorName),
        status = status,
        useCount = useCount,
        maxUseCount = coupon.maxUseCount,
        remainingUseCount = coupon.maxUseCount - useCount,
        usedAt = usedAt,
        orderId = orderId,
        claimedAt = claimedAt,
        expiresAt = expiresAt,
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
        CouponType.PERCENTAGE -> orderAmount.multiply(discountValue.divide(BigDecimal(100))).setScale(0, RoundingMode.HALF_UP)
        CouponType.FIXED_AMOUNT -> discountValue
        CouponType.FREE_SHIPPING -> shippingFee
    }

    val capped = maxDiscountAmount?.let { discount.min(it) } ?: discount

    // 상품 할인은 크리에이터 소계 초과 불가
    val bounded = if (couponType == CouponType.FREE_SHIPPING) {
        capped
    } else {
        capped.coerceAtMost(orderAmount)
    }
    return bounded.coerceAtLeast(BigDecimal.ZERO)  // 음수 할인 방지
}

fun List<Int>.encodeToTargetIds(): String? {
    return if (isEmpty()) null else Json.encodeToString(this)
}

fun String?.decodeToTargetIds(): List<Int> {
    if (isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<Int>>(this)
    } catch (_: Exception) {
        emptyList()
    }
}
