package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.util.TierAccessEvaluator
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.features.subscription.data.SubscriptionRepository

/** 상품 접근 판정, 잠금 상품만 구독 등급 조회 */
class ProductAccessService(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend fun canAccess(viewerId: Int?, product: ProductDao): Boolean {
        val viewerTier = if (viewerId != null && product.isLockedFor(viewerId)) {
            subscriptionRepository.findActiveTier(viewerId, product.creatorId)
        } else null

        return product.isAccessibleBy(viewerId, viewerTier)
    }

    suspend fun getAccessMap(viewerId: Int?, products: Collection<ProductDao>): Map<Int, Boolean> {
        val tiers = if (viewerId != null) {
            val lockedCreatorIds = products.filter { it.isLockedFor(viewerId) }.map { it.creatorId }.distinct()
            subscriptionRepository.findActiveTiersByCreators(viewerId, lockedCreatorIds)
        } else emptyMap()

        return products.associate { it.id.value to it.isAccessibleBy(viewerId, tiers[it.creatorId]) }
    }

    /** 본인 상품·FREE가 아니면 잠김 */
    private fun ProductDao.isLockedFor(viewerId: Int): Boolean =
        creatorId != viewerId && requiredTier != SubscriptionPlanTier.FREE

    private fun ProductDao.isAccessibleBy(viewerId: Int?, viewerTier: SubscriptionPlanTier?): Boolean =
        TierAccessEvaluator.canAccess(
            viewerId = viewerId,
            ownerId = creatorId,
            creatorId = creatorId,
            requiredTier = requiredTier,
            isSecret = false,
            viewerTier = viewerTier
        )
}
