package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

/** 등록 빌링 카드 메타데이터 */
@Serializable
data class BillingKeyResponse(
    val registered: Boolean,  // false면 미등록
    val cardCompany: String? = null,
    val cardNumberMasked: String? = null,
    val cardType: String? = null
)
