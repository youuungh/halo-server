package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class RoomClearedEvent(
    val roomId: Int  // 내 다른 기기에 남은 메시지도 비움
)
