package com.ninezero.features.notification.presentation.models.response

import com.ninezero.core.common.config.NotificationCategory
import com.ninezero.core.common.config.NotificationType
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class NotificationResponse(
    val id: Int,
    val recipientId: Int,
    val senderId: Int? = null,
    val senderUsername: String? = null,
    val senderAvatarUrl: String? = null,
    val type: NotificationType,
    val category: NotificationCategory,
    val title: String,
    val message: String,
    val targetType: String? = null,
    val targetId: Int? = null,
    val imageUrl: String? = null,
    val deepLink: String? = null,
    val isRead: Boolean,
    val readAt: LocalDateTime? = null,
    val createdAt: LocalDateTime
)
