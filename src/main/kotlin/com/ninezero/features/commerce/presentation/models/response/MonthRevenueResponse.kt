package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class MonthRevenueResponse(
    val month: String,  // "YYYY-MM"
    val revenue: String
)
