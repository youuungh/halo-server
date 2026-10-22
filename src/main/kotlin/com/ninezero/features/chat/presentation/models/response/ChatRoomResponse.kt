package com.ninezero.features.chat.presentation.models.response

import com.ninezero.core.common.util.PaginatedResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class ChatRoomResponse(
    val id: Int,
    val otherUser: UserSummaryResponse,
    val lastMessage: LastMessageInfo? = null,
    val unreadCount: Int,
    val isArchived: Boolean,
    val isBlockedByMe: Boolean,
    val isBlockedByOther: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

typealias ChatRoomListResponse = PaginatedResponse<ChatRoomResponse>
