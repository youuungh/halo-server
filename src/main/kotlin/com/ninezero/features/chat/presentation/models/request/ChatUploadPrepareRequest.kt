package com.ninezero.features.chat.presentation.models.request

import com.ninezero.core.common.config.ChatMessageType
import kotlinx.serialization.Serializable

@Serializable
data class ChatUploadPrepareRequest(
    // VIDEO 또는 FILE
    val messageType: ChatMessageType,
    val mimeType: String,
    val size: Long,
    val fileName: String? = null
)
