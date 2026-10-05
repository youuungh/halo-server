package com.ninezero.features.commerce.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdateReviewRequest(
    val rating: Int? = null,
    val content: String? = null,
    val images: List<String>? = null
)
