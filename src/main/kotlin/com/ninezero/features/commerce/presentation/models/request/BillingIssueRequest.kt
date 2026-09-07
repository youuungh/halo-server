package com.ninezero.features.commerce.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class BillingIssueRequest(
    val authKey: String  // customerKey는 서버 보관값
)
