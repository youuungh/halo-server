package com.ninezero.features.commerce.presentation.models.response

import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.ShippingStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class OrderCreatorGroupResponse(
    val creatorId: Int?,
    val creatorName: String? = null,
    val creatorAvatarUrl: String? = null,
    val creatorRole: String? = null,  // 인증 뱃지용 판매자 role
    val status: OrderStatus,
    val items: List<OrderItemResponse>,
    val subtotal: String,
    // 서버 계산값 그대로 표시
    val earnTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE,
    val earnRate: String = "0",
    val earnPoints: String = "0",
    val isSubscribed: Boolean = false,  // 구독 뱃지용, 현재 구독 여부
    val shippingFee: String,
    val couponDiscount: String = "0",
    val trackingNumber: String? = null,
    val carrier: String? = null,
    val shippingStatus: ShippingStatus,
    val estimatedDeliveryDate: LocalDateTime? = null,
    val actualDeliveryDate: LocalDateTime? = null,
    val refundAmount: String? = null,
    val refundReason: String? = null,
    val refundedAt: LocalDateTime? = null
)
