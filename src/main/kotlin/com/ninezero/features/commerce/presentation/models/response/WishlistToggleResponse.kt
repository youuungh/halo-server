package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class WishlistToggleResponse(
    val isWishlisted: Boolean,
    val wishlistCount: Int,
    val message: String
)
