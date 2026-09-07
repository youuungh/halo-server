package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class ProductSummaryResponse(
    val id: Int,
    val name: String,
    val price: String,
    val imageUrl: String?,
    val isAvailable: Boolean
)
