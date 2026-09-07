package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CommentThreadResponse(
    val comments: List<ThreadCommentResponse>,
    val post: CommentPostSummaryResponse
)
