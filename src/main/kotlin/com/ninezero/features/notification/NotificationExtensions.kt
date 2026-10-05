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
        id = id.value,
        recipientId = recipientId,
        senderId = senderId,
        senderUsername = senderUsername,
        senderAvatarUrl = senderAvatarUrl,
        type = type,
        category = type.getCategory(),
        title = title,
        message = message,
        targetType = targetType,
        targetId = targetId,
        imageUrl = imageUrl,
        deepLink = deepLink,
        isRead = isRead,
        readAt = readAt,
        createdAt = createdAt
    )
}

fun NotificationPreferenceDao.toNotificationPreferenceResponse(): NotificationPreferenceResponse {
    return NotificationPreferenceResponse(
        socialEnabled = socialEnabled,
        creatorActivityEnabled = creatorActivityEnabled,
        commerceEnabled = commerceEnabled,
        chatEnabled = chatEnabled,
        systemEnabled = systemEnabled
    )
}

fun defaultPreferenceResponse() = NotificationPreferenceResponse(
    socialEnabled = true,
    creatorActivityEnabled = true,
    commerceEnabled = true,
    chatEnabled = true,
    systemEnabled = true
)
