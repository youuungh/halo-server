package com.ninezero.features.notification.presentation.models.response

import com.ninezero.core.common.util.PaginatedResponse
import kotlinx.serialization.Serializable

@Serializable
data class NotificationListResponse(
    val notifications: PaginatedResponse<NotificationResponse>,
    val unreadCount: Int
)
