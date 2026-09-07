package com.ninezero.features.social.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class CommentRequest(
    val postId: Int,
    val content: String,
    val parentCommentId: Int? = null
)
