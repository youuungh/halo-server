package com.ninezero.features.point.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.Errors
import java.math.BigDecimal
import java.math.RoundingMode

fun getEarnRateForTier(tier: SubscriptionPlanTier): BigDecimal {
    return when (tier) {
        SubscriptionPlanTier.FREE -> Constants.Point.EARN_RATE_DECIMAL
        SubscriptionPlanTier.TIER1 -> Constants.Point.TIER1_EARN_RATE_DECIMAL
        SubscriptionPlanTier.TIER2 -> Constants.Point.TIER2_EARN_RATE_DECIMAL
    }
}

fun calcEarnByTier(orderAmount: BigDecimal, tier: SubscriptionPlanTier): BigDecimal {
    return orderAmount.multiply(getEarnRateForTier(tier)).setScale(0, RoundingMode.HALF_UP)
}

private fun calcMaxUsablePoints(orderAmount: BigDecimal): BigDecimal {
    return orderAmount
        .multiply(Constants.Point.MAX_USE_RATE_DECIMAL)  // 주문 금액의 50% 상한
        .setScale(0, RoundingMode.HALF_UP)
}

fun pointAmountError(
    pointsToUse: BigDecimal,
    currentBalance: BigDecimal,
    orderAmount: BigDecimal
): String? {
    if (currentBalance < pointsToUse) {
        return Errors.Point.INSUFFICIENT_POINTS
    }

    if (pointsToUse < Constants.Point.MIN_USE_AMOUNT_DECIMAL) {
        return "최소 ${Constants.Point.MIN_USE_AMOUNT_DECIMAL.toInt()}원 이상부터 사용 가능합니다."
    }

    val maxUsable = calcMaxUsablePoints(orderAmount)
    if (pointsToUse > maxUsable) {
        return "최대 ${maxUsable}원까지 사용할 수 있습니다. (주문 금액의 50%)"
    }

    return null
}
