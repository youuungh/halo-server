package com.ninezero.features.subscription.presentation.models.response

import com.ninezero.core.common.config.SubscriptionPlanTier
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionPlanResponse(
    val id: Int,
    val creatorId: Int,
    val creatorUsername: String,
    val name: String,
    val tier: SubscriptionPlanTier,
    val description: String,
    val price: String,
    val benefits: List<String>,
    val subscriberCount: Int,
    val isActive: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
