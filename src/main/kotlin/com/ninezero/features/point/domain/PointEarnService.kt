package com.ninezero.features.point.domain

import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.util.calcEarnByTier
import java.math.BigDecimal

class PointEarnService(
    private val pointService: PointService
) {

    /** 티어 차등 적립, 호출부 트랜잭션에 합류 */
    fun earnPointsFromOrder(
        userId: Int,
        orderId: Int,
        creatorAmountMap: Map<Int, BigDecimal>,
        creatorTierMap: Map<Int, SubscriptionPlanTier>
    ): BigDecimal {
        var totalEarned = BigDecimal.ZERO

        for ((creatorId, amount) in creatorAmountMap) {
            val tier = creatorTierMap[creatorId] ?: SubscriptionPlanTier.FREE
            totalEarned = totalEarned.add(calcEarnByTier(amount, tier))
        }

        if (totalEarned <= BigDecimal.ZERO) return BigDecimal.ZERO

        return pointService.earnPointsInTransaction(
            userId = userId,
            amount = totalEarned,
            orderId = orderId,
            description = "주문 확정으로 인한 포인트 적립 (티어별 차등)"
        )
    }
}
