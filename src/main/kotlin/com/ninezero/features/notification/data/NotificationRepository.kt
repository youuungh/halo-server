package com.ninezero.features.notification.data

import com.ninezero.core.common.config.NotificationType
import com.ninezero.core.database.entities.notification.NotificationDao
import kotlinx.datetime.LocalDateTime

data class NotificationData(
    val recipientId: Int,
    val senderId: Int? = null,
    val type: NotificationType,
    val title: String,
    val message: String,
    val targetType: String? = null,
    val targetId: Int? = null,
    val imageUrl: String? = null,
    val deepLink: String? = null,
    val metadata: String? = null
)

interface NotificationRepository {

    // 알림 생성/읽음 처리/삭제
    suspend fun createNotification(
        recipientId: Int,
        senderId: Int? = null,
        type: NotificationType,
        title: String,
        message: String,
        targetType: String? = null,
        targetId: Int? = null,
        imageUrl: String? = null,
        deepLink: String? = null,
        metadata: String? = null
    ): NotificationDao

    suspend fun batchCreateNotifications(
        notifications: List<NotificationData>
    ): List<Int>

    suspend fun markAsRead(notificationId: Int, userId: Int): Boolean
    suspend fun markAllAsRead(userId: Int): Boolean

    suspend fun markAsReadByTarget(userId: Int, type: NotificationType, targetId: Int): Boolean

    suspend fun markChatNotificationsAsRead(userId: Int, roomId: Int): Int
    suspend fun deleteNotification(notificationId: Int, userId: Int): Boolean

    // 탈퇴 정리
    suspend fun deleteAllByUser(userId: Int): Int

    // 알림 조회
    suspend fun findById(id: Int): NotificationDao?

    suspend fun findByIds(ids: List<Int>): List<NotificationDao>
    suspend fun findActiveByMetadata(metadata: String): NotificationDao?
    suspend fun findActiveByMetadataList(metadataList: List<String>): List<NotificationDao>
    suspend fun findUserNotifications(
        userId: Int,
        isRead: Boolean? = null,
        page: Int,
        limit: Int
    ): List<NotificationDao>

    // 카운트
    suspend fun countUnreadNotifications(userId: Int): Long
    suspend fun countTotalNotifications(userId: Int, isRead: Boolean? = null): Int

    // 정리
    suspend fun deactivateOlderThan(cutoff: LocalDateTime): Int
}
