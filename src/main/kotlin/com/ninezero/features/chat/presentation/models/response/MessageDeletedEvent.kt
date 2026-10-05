package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class MessageDeletedEvent(
    val roomId: Int,
    val messageId: Int  // 상대방 화면 말풍선을 삭제 메시지로 전환
)
