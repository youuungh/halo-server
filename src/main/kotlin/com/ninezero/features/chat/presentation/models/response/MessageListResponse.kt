package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class MessageListResponse(
    val messages: List<MessageResponse>,
    val lastMessageId: Int?,
    val lastReadMessageId: Int?,
    val hasNext: Boolean
)
