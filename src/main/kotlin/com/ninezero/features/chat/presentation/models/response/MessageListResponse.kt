package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class MessageListResponse(
    val messages: List<MessageResponse>,
    val lastMessageId: Int? = null,
    val lastReadMessageId: Int? = null,
    val hasNext: Boolean
)
