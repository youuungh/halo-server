package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageReadEvent(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "MESSAGE_READ",
    val roomId: Int,
    val messageIds: List<Int>,
    val readerId: Int,
    val readAt: String
)
