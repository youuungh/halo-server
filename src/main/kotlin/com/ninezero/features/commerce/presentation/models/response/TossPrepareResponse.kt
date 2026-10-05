package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

/** 토스 결제 준비 응답 */
@Serializable
data class TossPrepareResponse(
    val orderId: String,  // 토스 거래ID
    val orderName: String,
    val amount: String,
    val customerName: String? = null
)
