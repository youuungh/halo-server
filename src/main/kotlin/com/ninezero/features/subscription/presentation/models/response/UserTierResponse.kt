package com.ninezero.features.subscription.presentation.models.response

import com.ninezero.core.common.config.SubscriptionPlanTier
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class UserTierResponse(
    val tier: SubscriptionPlanTier,
    val isSubscribed: Boolean,
    val subscriptionId: Int? = null,
    val planName: String? = null,
    val planPrice: String? = null,
    val expiresAt: LocalDateTime? = null,
    val daysRemaining: Int? = null
)
