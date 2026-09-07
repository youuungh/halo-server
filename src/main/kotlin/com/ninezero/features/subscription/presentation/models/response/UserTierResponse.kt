package com.ninezero.features.subscription.presentation.models.response

import com.ninezero.core.common.config.SubscriptionPlanTier
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class UserTierResponse(
    val tier: SubscriptionPlanTier,
    val isSubscribed: Boolean,
    val subscriptionId: Int?,
    val planName: String?,
    val planPrice: String?,
    val expiresAt: LocalDateTime?,
    val daysRemaining: Int?
)
