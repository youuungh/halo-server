package com.ninezero.features.commerce.presentation.models.response

import com.ninezero.core.common.config.ShippingStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class ShippingResponse(
    val orderId: Int,
    val creatorId: Int? = null,
    val trackingNumber: String?,
    val carrier: String?,
    val shippingStatus: ShippingStatus,
    val estimatedDeliveryDate: LocalDateTime?,
    val actualDeliveryDate: LocalDateTime?,
    val courierName: String? = null,
    val deliveryStatusText: String? = null,
    val lastProgressAt: String? = null,
    val queriedAt: String? = null,
    val progresses: List<ShippingProgressResponse> = emptyList()
)
