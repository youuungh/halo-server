package com.ninezero.features.chat.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class MessageReadEvent(
    val roomId: Int,
    val messageIds: List<Int>,
    val readerId: Int,
    val readAt: LocalDateTime
)
