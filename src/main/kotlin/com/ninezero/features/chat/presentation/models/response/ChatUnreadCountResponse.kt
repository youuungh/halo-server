package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class ChatUnreadCountResponse(
    val totalUnreadCount: Int,
    val roomUnreadCounts: Map<Int, Int>  // roomId -> unreadCount
)
