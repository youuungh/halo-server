package com.ninezero.features.notification.data

import com.ninezero.core.common.config.NotificationType
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.notification.NotificationDao
import com.ninezero.core.database.entities.notification.NotificationTable
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.update

class NotificationRepositoryImpl : NotificationRepository {

    /** 알림 생성 */
    override suspend fun createNotification(
        recipientId: Int,
        senderId: Int?,
        type: NotificationType,
        title: String,
        message: String,
        targetType: String?,
        targetId: Int?,
        imageUrl: String?,
        deepLink: String?,
        metadata: String?
    ): NotificationDao {
        return NotificationDao.new {
            this.recipientId = recipientId
            this.senderId = senderId
            this.type = type
            this.title = title
            this.message = message
            this.targetType = targetType
            this.targetId = targetId
            this.imageUrl = imageUrl
            this.deepLink = deepLink
            this.metadata = metadata
            this.isRead = false
            this.readAt = null
            this.isPushSent = false
            this.pushSentAt = null
            this.isActive = true
        }
    }

    /** 알림 일괄 생성 */
    override suspend fun batchCreateNotifications(
        notifications: List<NotificationData>
    ): List<Int> {
        if (notifications.isEmpty()) return emptyList()

        val now = nowUtc()

        val insertedRows = NotificationTable.batchInsert(notifications) { data ->
            this[NotificationTable.recipientId] = data.recipientId
            this[NotificationTable.senderId] = data.senderId
            this[NotificationTable.type] = data.type
            this[NotificationTable.title] = data.title
            this[NotificationTable.message] = data.message
            this[NotificationTable.targetType] = data.targetType
            this[NotificationTable.targetId] = data.targetId
            this[NotificationTable.imageUrl] = data.imageUrl
            this[NotificationTable.deepLink] = data.deepLink
            this[NotificationTable.metadata] = data.metadata
            this[NotificationTable.isRead] = false
            this[NotificationTable.readAt] = null
            this[NotificationTable.isPushSent] = false
            this[NotificationTable.pushSentAt] = null
            this[NotificationTable.isActive] = true
            this[NotificationTable.createdAt] = now
            this[NotificationTable.updatedAt] = now
        }

        return insertedRows.map { it[NotificationTable.id].value }
    }

    /** 본인 수신 알림 읽음 처리 */
    override suspend fun markAsRead(notificationId: Int, userId: Int): Boolean {
        val notification = NotificationDao.findById(notificationId)
            ?.takeIf { it.recipientId == userId && it.isActive }
            ?: return false

        if (!notification.isRead) {  // 이미 읽음이면 readAt 유지
            notification.isRead = true
            notification.readAt = nowUtc()
        }

        return true
    }

    /** 알림 읽음 처리 */
    override suspend fun markAsReadByTarget(userId: Int, type: NotificationType, targetId: Int): Boolean {
        val notification = NotificationDao.find {
            (NotificationTable.recipientId eq userId) and
                    (NotificationTable.type eq type) and
                    (NotificationTable.targetId eq targetId) and
                    (NotificationTable.isActive eq true)
        }
            .orderBy(NotificationTable.createdAt to SortOrder.DESC, NotificationTable.id to SortOrder.DESC)
            // 다중 행 타입은 이 경로 금지
            .limit(1)
            .firstOrNull()
            ?: return false

        if (!notification.isRead) {
            notification.isRead = true
            notification.readAt = nowUtc()
        }

        return true
    }

    /** 채팅방 CHAT_MESSAGE 알림 일괄 읽음 처리 */
    override suspend fun markChatNotificationsAsRead(userId: Int, roomId: Int): Int {
        val now = nowUtc()

        // 채팅은 메시지마다 쌓임
        return NotificationTable.update({
            (NotificationTable.recipientId eq userId) and
                    (NotificationTable.type eq NotificationType.CHAT_MESSAGE) and
                    (NotificationTable.targetId eq roomId) and
                    (NotificationTable.isRead eq false) and
                    (NotificationTable.isActive eq true)
        }) {
            it[isRead] = true
            it[readAt] = now
            it[updatedAt] = now
        }
    }

    /** 수신자의 안읽은 알림 전부 읽음 처리 */
    override suspend fun markAllAsRead(userId: Int): Boolean {
        val now = nowUtc()

        val updated = NotificationTable.update({
            (NotificationTable.recipientId eq userId) and
                    (NotificationTable.isRead eq false) and
                    (NotificationTable.isActive eq true)
        }) {
            it[isRead] = true
            it[readAt] = now
            it[updatedAt] = now
        }

        return updated > 0
    }

    /** 본인 수신 알림 삭제 */
    override suspend fun deleteNotification(notificationId: Int, userId: Int): Boolean {
        val notification = NotificationDao.findById(notificationId)
            ?.takeIf { it.recipientId == userId && it.isActive }
            ?: return false

        notification.isActive = false

        return true
    }

    /** 탈퇴 정리용 알림 비활성화 */
    override suspend fun deleteAllByUser(userId: Int): Int {
        return NotificationTable.update({
            (NotificationTable.recipientId eq userId) and (NotificationTable.isActive eq true)
        }) {
            it[isActive] = false
            it[updatedAt] = nowUtc()
        }
    }

    /** 알림 조회 */
    override suspend fun findById(id: Int): NotificationDao? {
        return NotificationDao.findById(id)?.takeIf { it.isActive }
    }

    /** 알림 일괄 조회 */
    override suspend fun findByIds(ids: List<Int>): List<NotificationDao> {
        if (ids.isEmpty()) return emptyList()
        return NotificationDao.find { NotificationTable.id inList ids }.filter { it.isActive }
    }

    /** metadata가 일치하는 알림 최신 1건 */
    override suspend fun findActiveByMetadata(metadata: String): NotificationDao? {
        return NotificationDao.find {
            (NotificationTable.metadata eq metadata) and
                    (NotificationTable.isActive eq true)
        }
            .orderBy(NotificationTable.createdAt to SortOrder.DESC, NotificationTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
    }

    /** metadata 목록의 알림 전부 조회 */
    override suspend fun findActiveByMetadataList(metadataList: List<String>): List<NotificationDao> {
        if (metadataList.isEmpty()) return emptyList()

        return NotificationDao.find {
            (NotificationTable.metadata inList metadataList.distinct()) and  // 중복 metadata 제거
                    (NotificationTable.isActive eq true)
        }.toList()
    }

    /** 수신자의 알림 목록 조회 */
    override suspend fun findUserNotifications(
        userId: Int,
        isRead: Boolean?,
        page: Int,
        limit: Int
    ): List<NotificationDao> {
        var query = (NotificationTable.recipientId eq userId) and (NotificationTable.isActive eq true)

        if (isRead != null) {
            query = query and (NotificationTable.isRead eq isRead)
        }

        return NotificationDao.find { query }
            .orderBy(NotificationTable.createdAt to SortOrder.DESC, NotificationTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 수신자의 안읽은 알림 수 */
    override suspend fun countUnreadNotifications(userId: Int): Long {
        return NotificationDao.find {
            (NotificationTable.recipientId eq userId) and
                    (NotificationTable.isRead eq false) and
                    (NotificationTable.isActive eq true)
        }.count()
    }

    /** 수신자의 알림 수 */
    override suspend fun countTotalNotifications(userId: Int, isRead: Boolean?): Int {
        var query = (NotificationTable.recipientId eq userId) and (NotificationTable.isActive eq true)

        if (isRead != null) {
            query = query and (NotificationTable.isRead eq isRead)
        }

        return NotificationDao.find { query }.count().toInt()
    }

    /** 오래된 알림 일괄 비활성화 */
    override suspend fun deactivateOlderThan(cutoff: LocalDateTime): Int {
        val now = nowUtc()

        return NotificationTable.update({
            (NotificationTable.isActive eq true) and
                    (NotificationTable.createdAt lessEq cutoff)
        }) {
            it[isActive] = false
            it[updatedAt] = now
        }
    }
}
