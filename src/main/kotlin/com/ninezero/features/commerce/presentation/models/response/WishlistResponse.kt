package com.ninezero.features.commerce.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class WishlistResponse(
    val id: Int,
    val userId: Int,
    val product: WishlistProductResponse,
    val createdAt: LocalDateTime
)
