package com.ninezero.features.subscription.presentation.models.response

import com.ninezero.core.common.util.PaginatedResponse
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionListResponse(
    val subscriptions: PaginatedResponse<SubscriptionResponse>,
    val activeCount: Int
)
