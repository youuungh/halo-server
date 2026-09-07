package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class ProductMessageInfo(
    val productId: Int,
    val name: String,
    val price: String,
    val originalPrice: String? = null,
    val discountRate: Int? = null,
    val imageUrl: String?,
    val canAccess: Boolean = true,
    val creatorId: Int? = null,
    val creatorName: String? = null,
    val requiredTier: String? = null  // 잠금 티어 표시용
)
