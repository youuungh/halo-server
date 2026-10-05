package com.ninezero.features.notification.presentation.models.response

import com.ninezero.core.common.util.CursorResponse
import kotlinx.serialization.Serializable

@Serializable
data class NotificationListResponse(
    val notifications: CursorResponse<NotificationResponse>,
    val unreadCount: Int
)
