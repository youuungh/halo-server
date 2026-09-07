package com.ninezero.features.commerce.domain

import kotlinx.serialization.Serializable

@Serializable
data class OrderItemData(
    val productId: Int,
    val quantity: Int,
    val price: String,
    val originalPrice: String? = null  // 할인 전 원가
)
