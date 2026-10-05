package com.ninezero.features.social.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdateCommentRequest(
    val content: String
)
