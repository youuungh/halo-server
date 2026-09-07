package com.ninezero.features.commerce.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class CartRequest(
    val productId: Int,
    val quantity: Int
)
