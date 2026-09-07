package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class OrderItemResponse(
    val id: Int,
    val productId: Int,
    val productName: String,
    val productImageUrl: String?,
    val quantity: Int,
    val price: String,
    val originalPrice: String? = null,
    val subtotal: String,
    val creatorId: Int? = null,
    val creatorName: String? = null,
    val creatorAvatarUrl: String? = null,
    val hasReview: Boolean = false,
    val reviewId: Int? = null,
    val isProductActive: Boolean = true  // 판매 가능 여부, 삭제 시 false
)
