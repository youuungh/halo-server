package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class ShippingProgressResponse(
    val dateTime: String? = null,
    val location: String? = null,
    val status: String? = null,
    val statusCode: String? = null,
    val description: String? = null
)
