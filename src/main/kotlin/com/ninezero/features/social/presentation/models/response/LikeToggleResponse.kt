package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class LikeToggleResponse(
    val isLiked: Boolean,
    val likeCount: Int,
    val message: String? = null
)
