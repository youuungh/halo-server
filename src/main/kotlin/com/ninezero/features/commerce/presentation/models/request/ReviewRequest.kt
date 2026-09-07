package com.ninezero.features.commerce.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class ReviewRequest(
    val orderId: Int,
    val productId: Int,
    val rating: Int,
    val content: String,
    val images: List<String> = emptyList()
)
