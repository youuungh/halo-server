package com.ninezero.features.notification

import com.ninezero.core.common.config.getCategory
import com.ninezero.core.database.entities.notification.NotificationDao
import com.ninezero.core.database.entities.notification.NotificationPreferenceDao
import com.ninezero.features.notification.presentation.models.response.NotificationPreferenceResponse
import com.ninezero.features.notification.presentation.models.response.NotificationResponse

fun NotificationDao.toNotificationResponse(
    senderUsername: String?,
    senderAvatarUrl: String? = null
): NotificationResponse {
    return NotificationResponse(
        id = this.id.value,
        recipientId = this.recipientId,
        senderId = this.senderId,
        senderUsername = senderUsername,
        senderAvatarUrl = senderAvatarUrl,
        type = this.type,
        category = this.type.getCategory(),
        title = this.title,
        message = this.message,
        targetType = this.targetType,
        targetId = this.targetId,
        imageUrl = this.imageUrl,
        deepLink = this.deepLink,
        isRead = this.isRead,
        readAt = this.readAt,
        createdAt = this.createdAt
    )
}

fun NotificationPreferenceDao.toPreferenceResponse() = NotificationPreferenceResponse(
    socialEnabled = socialEnabled,
    creatorActivityEnabled = creatorActivityEnabled,
    commerceEnabled = commerceEnabled,
    chatEnabled = chatEnabled,
    systemEnabled = systemEnabled
)

fun defaultPreferenceResponse() = NotificationPreferenceResponse(
    socialEnabled = true,
    creatorActivityEnabled = true,
    commerceEnabled = true,
    chatEnabled = true,
    systemEnabled = true
)
