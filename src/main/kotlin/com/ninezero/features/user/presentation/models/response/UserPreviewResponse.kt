package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class UserPreviewResponse(
    val id: Int,
    val avatarUrl: String? = null,
    val avatarThumbUrl: String? = null
)
