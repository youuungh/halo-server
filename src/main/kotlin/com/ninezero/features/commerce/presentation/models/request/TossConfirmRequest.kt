package com.ninezero.features.commerce.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class TossConfirmRequest(
    val paymentKey: String,
    val orderId: Int,  // 토스 transactionId와 다름
    val amount: Long
)
