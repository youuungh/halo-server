package com.ninezero.features.commerce.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class CartItemResponse(
    val id: Int,
    val product: ProductResponse,
    val quantity: Int,
    val subtotal: String,  // 소계
    val createdAt: LocalDateTime
)
