package com.ninezero.features.subscription.presentation.models.response

import com.ninezero.core.common.config.SubscriptionStatus
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.util.PaginatedResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionResponse(
    val id: Int,
    val userId: Int,
    val creatorId: Int,
    val creatorUsername: String,
    val creatorAvatarUrl: String? = null,
    val creatorRole: UserRole? = null,
    val planId: Int,
    val planName: String,
    val planPrice: String,
    val status: SubscriptionStatus,
    val startedAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val cancelledAt: LocalDateTime? = null,
    val autoRenew: Boolean,
    val daysRemaining: Int,
    val createdAt: LocalDateTime
)

typealias SubscriberListResponse = PaginatedResponse<UserSummaryResponse>
