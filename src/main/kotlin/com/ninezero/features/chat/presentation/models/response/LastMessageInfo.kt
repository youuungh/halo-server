package com.ninezero.features.chat.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class LastMessageInfo(
    val preview: String,
    val timestamp: LocalDateTime,
    val isFromMe: Boolean
)
