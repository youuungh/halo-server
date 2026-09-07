package com.ninezero.features.subscription.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionStatusResponse(
    val isSubscribed: Boolean,
    val subscriptionId: Int?,
    val planId: Int?,
    val planName: String?,
    val expiresAt: LocalDateTime?,
    val daysRemaining: Int?,
    val autoRenew: Boolean
)
