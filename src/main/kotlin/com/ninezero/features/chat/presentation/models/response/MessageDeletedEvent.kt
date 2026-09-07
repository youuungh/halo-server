package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageDeletedEvent(
    // 기본값도 직렬화 강제
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "MESSAGE_DELETED",
    val roomId: Int,
    val messageId: Int  // 상대방 화면 말풍선을 삭제 메시지로 전환
)
