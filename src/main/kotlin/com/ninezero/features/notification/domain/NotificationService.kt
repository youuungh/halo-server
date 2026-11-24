package com.ninezero.features.notification.domain

import com.ninezero.core.common.config.NotificationType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.CursorResponse
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toCursorPage
import com.ninezero.core.common.util.validateCursorLimit
import com.ninezero.features.notification.data.NotificationPreferenceRepository
import com.ninezero.features.notification.data.NotificationRepository
import com.ninezero.features.notification.defaultPreferenceResponse
import com.ninezero.features.notification.presentation.models.response.NotificationListResponse
import com.ninezero.features.notification.presentation.models.response.NotificationPreferenceResponse
import com.ninezero.features.notification.presentation.models.response.UnreadCountResponse
import com.ninezero.features.notification.toNotificationPreferenceResponse
import com.ninezero.features.notification.toNotificationResponse
import com.ninezero.features.user.data.UserRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** 내 알림 조회·읽음·설정·정리 */
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    private val preferenceRepository: NotificationPreferenceRepository
) {
    // =====  알림 조회 =====

    suspend fun getMyNotifications(
        userId: Int,
        isRead: Boolean?,
        cursor: String?,
        limit: Int
    ): NotificationListResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            val (notifications, nextCursor) = notificationRepository.findUserNotifications(userId, isRead, pageCursor, validLimit + 1)
                .toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }
            val unreadCount = notificationRepository.countUnreadNotifications(userId)

            val senderIds = notifications.mapNotNull { it.senderId }.distinct()

            val senders = if (senderIds.isNotEmpty()) {
                userRepository.findUsersByIds(senderIds).associateBy { it.id.value }  // 일괄 조회
            } else {
                emptyMap()
            }

            val notificationResponses = notifications.map { notification ->
                val sender = notification.senderId?.let { senderId -> senders[senderId] }
                notification.toNotificationResponse(
                    senderUsername = sender?.username,
                    senderAvatarUrl = sender?.profile?.let { it.avatarThumbUrl ?: it.avatarUrl }
                )
            }

            NotificationListResponse(
                notifications = CursorResponse(notificationResponses, nextCursor, hasNext = nextCursor != null),
                unreadCount = unreadCount
            )
        }
    }

    suspend fun getUnreadCount(userId: Int): UnreadCountResponse {
        return query {
            val count = notificationRepository.countUnreadNotifications(userId)
            UnreadCountResponse(count)
        }
    }

    // =====  읽음 처리·삭제 =====

    suspend fun markAsReadByTarget(userId: Int, type: NotificationType, targetId: Int) {
        query { notificationRepository.markAsReadByTarget(userId, type, targetId) }  // 대상 없으면 조용히 무시
    }

    suspend fun markAsRead(notificationId: Int, userId: Int) {
        val success = query {
            notificationRepository.markAsRead(notificationId, userId)
        }

        if (!success) {
            throw NotFoundException(Errors.Notification.NOTIFICATION_NOT_FOUND_OR_NO_PERMISSION)  // 대상 없거나 권한 없으면
        }
    }

    suspend fun markAllAsRead(userId: Int) {
        query {
            notificationRepository.markAllAsRead(userId)  // 안읽은 알림 없어도 성공
        }
    }

    suspend fun deleteNotification(notificationId: Int, userId: Int) {
        val success = query {
            notificationRepository.deleteNotification(notificationId, userId)
        }

        if (!success) {
            throw NotFoundException(Errors.Notification.NOTIFICATION_NOT_FOUND_OR_NO_PERMISSION)  // 대상 없거나 권한 없으면
        }
    }

    // ===== 알림 설정 =====

    suspend fun getPreferences(userId: Int): NotificationPreferenceResponse {
        return query {
            preferenceRepository.findByUserId(userId)?.toNotificationPreferenceResponse() ?: defaultPreferenceResponse()
        }
    }

    suspend fun updatePreferences(
        userId: Int,
        socialEnabled: Boolean?,
        creatorActivityEnabled: Boolean?,
        commerceEnabled: Boolean?,
        chatEnabled: Boolean?,
        systemEnabled: Boolean?
    ): NotificationPreferenceResponse {
        return query {
            preferenceRepository.update(
                userId = userId,
                socialEnabled = socialEnabled,
                creatorActivityEnabled = creatorActivityEnabled,
                commerceEnabled = commerceEnabled,
                chatEnabled = chatEnabled,
                systemEnabled = systemEnabled
            ).toNotificationPreferenceResponse()
        }
    }

    /** 오래된 알림 정리 */
    suspend fun cleanupOldNotifications(retentionDays: Int): Int {
        val cutoff = (Clock.System.now() - retentionDays.days).toLocalDateTime(TimeZone.UTC)

        return query {
            notificationRepository.deactivateOlderThan(cutoff)
        }
    }
}
