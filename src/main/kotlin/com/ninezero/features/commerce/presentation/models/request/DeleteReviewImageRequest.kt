package com.ninezero.features.commerce.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class DeleteReviewImageRequest(
    val imageUrl: String
)
