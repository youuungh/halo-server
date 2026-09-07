package com.ninezero.features.subscription.presentation.models.request

import com.ninezero.core.common.config.SubscriptionPlanTier
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionPlanRequest(
    val name: String,
    val tier: SubscriptionPlanTier,
    val description: String,
    val price: String,
    val benefits: List<String>
)