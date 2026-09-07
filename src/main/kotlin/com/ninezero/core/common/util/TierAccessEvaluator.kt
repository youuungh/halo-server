package com.ninezero.core.common.util

import com.ninezero.core.common.config.SubscriptionPlanTier

/** 잠금 콘텐츠 접근 판정 */
object TierAccessEvaluator {
    fun canAccess(
        viewerId: Int?,
        ownerId: Int,
        creatorId: Int?,
        requiredTier: SubscriptionPlanTier,
        isSecret: Boolean,
        viewerTier: SubscriptionPlanTier?,
    ): Boolean {
        // 순서 고정: 본인 → 비밀글 → FREE → 티어
        if (viewerId != null && (viewerId == ownerId || viewerId == creatorId)) return true
        if (isSecret) return false
        if (requiredTier == SubscriptionPlanTier.FREE) return true
        return viewerTier?.canAccess(requiredTier) ?: false
    }
}
