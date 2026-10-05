package com.ninezero.features.commerce.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class BillingPayRequest(
    val orderId: Int  // 앱 내부 주문 ID
)
