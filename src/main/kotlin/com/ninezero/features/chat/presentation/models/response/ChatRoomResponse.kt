package com.ninezero.features.chat.presentation.models.response

import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class ChatRoomResponse(
    val id: Int,
    val otherUser: UserSummaryResponse,
    val lastMessage: LastMessageInfo?,
    val unreadCount: Int,
    val isArchived: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
