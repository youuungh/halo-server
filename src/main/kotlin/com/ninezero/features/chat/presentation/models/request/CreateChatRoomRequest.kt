package com.ninezero.features.chat.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class CreateChatRoomRequest(
    val otherUserId: Int
)