package com.ninezero.features.commerce.presentation.models.response

import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.PaymentProvider
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.config.ShippingStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class OrderResponse(
    val id: Int,
    val userId: Int,
    val orderNumber: String,
    val items: List<OrderItemResponse>,
    val creatorGroups: List<OrderCreatorGroupResponse> = emptyList(),
    val totalPrice: String,
    val status: OrderStatus,
    val shippingAddress: String,
    val shippingPhone: String,
    val shippingName: String,
    val paymentProvider: PaymentProvider?,
    val paymentStatus: PaymentStatus?,
    val memo: String?,
    val pointsUsed: String,
    val couponDiscount: String,
    val couponCode: String?,
    val shippingFee: String,
    /** 그룹별 적립 합계 */
    val totalEarnPoints: String = "0",  // 서버 계산값
    val trackingNumber: String? = null,
    val carrier: String? = null,
    val shippingStatus: ShippingStatus,
    val estimatedDeliveryDate: LocalDateTime? = null,
    val actualDeliveryDate: LocalDateTime? = null,
    val refundAmount: String? = null,
    val refundReason: String? = null,
    val refundedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
