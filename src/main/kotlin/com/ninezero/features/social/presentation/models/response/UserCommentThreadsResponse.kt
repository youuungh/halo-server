package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class UserCommentThreadsResponse(
    val threads: List<CommentThreadResponse>,
    val totalCount: Int,
    val page: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)
