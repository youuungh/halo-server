package com.ninezero.features.social.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class CommentRequest(
    val postId: Int,
    val content: String,
    val parentCommentId: Int? = null,
    val mediaDraftIds: List<Int> = emptyList(),  // 표시 순서대로
    val clientRequestId: String? = null  // 같은 요청이 반복되면 만든 댓글을 그대로 응답
)
