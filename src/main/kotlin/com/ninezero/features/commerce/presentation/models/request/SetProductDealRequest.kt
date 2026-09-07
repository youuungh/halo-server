package com.ninezero.features.commerce.presentation.models.request

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class SetProductDealRequest(
    val dealPrice: String? = null,
    val startAt: LocalDateTime? = null,
    val endAt: LocalDateTime? = null
)
