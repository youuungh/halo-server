package com.ninezero.features.point.domain

import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.util.calcEarnByTier
import com.ninezero.core.common.util.logger
import java.math.BigDecimal

class PointEarnService(
    private val pointService: PointService
) {
    private val logger = logger()

    /** 티어 차등 적립 */
    suspend fun earnPointsFromOrder(
        userId: Int,
        orderId: Int,
        creatorAmountMap: Map<Int, BigDecimal>,
        creatorTierMap: Map<Int, SubscriptionPlanTier>
    ): BigDecimal {
        try {
            var totalEarned = BigDecimal.ZERO

            for ((creatorId, amount) in creatorAmountMap) {
                val tier = creatorTierMap[creatorId] ?: SubscriptionPlanTier.FREE
                val earnAmount = calcEarnByTier(amount, tier)
                totalEarned = totalEarned.add(earnAmount)
            }

            if (totalEarned > BigDecimal.ZERO) {
                pointService.earnPoints(
                    userId = userId,
                    amount = totalEarned,
                    orderId = orderId,
                    description = "주문 확정으로 인한 포인트 적립 (티어별 차등)"
                )
            }

            return totalEarned
        } catch (e: Exception) {
            logger.error("포인트 적립 실패 - userId: $userId, orderId: $orderId, error: ${e.message}", e)
            return BigDecimal.ZERO
        }
    }
}
