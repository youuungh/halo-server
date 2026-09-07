package com.ninezero.features.notification.presentation.models.response

import com.ninezero.core.common.config.NotificationCategory
import com.ninezero.core.common.config.NotificationType
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class NotificationResponse(
    val id: Int,
    val recipientId: Int,
    val senderId: Int?,
    val senderUsername: String?,
    val senderAvatarUrl: String?,
    val type: NotificationType,
    val category: NotificationCategory,
    val title: String,
    val message: String,
    val targetType: String?,
    val targetId: Int?,
    val imageUrl: String?,
    val deepLink: String?,
    val isRead: Boolean,
    val readAt: LocalDateTime?,
    val createdAt: LocalDateTime
)
