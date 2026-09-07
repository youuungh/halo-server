package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class MarkMessagesReadResponse(
    val lastReadMessageId: Int?
)
