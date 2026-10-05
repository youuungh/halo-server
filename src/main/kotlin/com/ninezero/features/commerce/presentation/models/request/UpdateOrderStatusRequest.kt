package com.ninezero.features.commerce.presentation.models.request

import com.ninezero.core.common.config.OrderStatus
import kotlinx.serialization.Serializable

@Serializable
data class UpdateOrderStatusRequest(
    val status: OrderStatus
)
