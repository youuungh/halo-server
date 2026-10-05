package com.ninezero.features.subscription.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionStatusResponse(
    val isSubscribed: Boolean,
    val subscriptionId: Int? = null,
    val planId: Int? = null,
    val planName: String? = null,
    val expiresAt: LocalDateTime? = null,
    val daysRemaining: Int? = null,
    val autoRenew: Boolean
)
