package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CartResponse(
    val items: List<CartItemResponse>,
    val totalItems: Int,
    val totalPrice: String
)
