package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class ChatImageUploadResponse(
    val imageUrls: List<String>,
    val thumbnailUrls: List<String>
)
