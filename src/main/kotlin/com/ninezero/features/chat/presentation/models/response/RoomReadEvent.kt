package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class RoomReadEvent(
    val roomId: Int  // 내 다른 기기의 안 읽음 수를 0으로
)
