package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class BestSellerResponse(
    val productId: Int,
    val productName: String,
    val productImageUrl: String? = null,
    val quantity: Int,  // 판매 수량
    val revenue: String
)
