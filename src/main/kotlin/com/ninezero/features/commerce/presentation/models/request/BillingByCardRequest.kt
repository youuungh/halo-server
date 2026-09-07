package com.ninezero.features.commerce.presentation.models.request

import kotlinx.serialization.Serializable

/** 테스트용 카드 직접 입력 발급 요청 */
@Serializable
data class BillingByCardRequest(
    val cardNumber: String,
    val expirationYear: String,
    val expirationMonth: String,
    val identityNumber: String,
    val cardPassword: String? = null
)
