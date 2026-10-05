package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class SalesStatusBreakdownResponse(
    val pending: Int,
    val confirmed: Int,
    val shipping: Int,
    val delivered: Int,
    val cancelled: Int
)
