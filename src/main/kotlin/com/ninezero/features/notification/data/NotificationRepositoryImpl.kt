package com.ninezero.features.notification.data

import com.ninezero.core.common.config.NotificationType
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.afterNewest
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
        }
    }

    /** 알림 일괄 생성 */
    override suspend fun batchCreateNotifications(
        notifications: List<NotificationData>
    ): List<NotificationDao> {
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
            this[NotificationTable.isActive] = true
            this[NotificationTable.createdAt] = now
            this[NotificationTable.updatedAt] = now
        }

        return insertedRows.map { NotificationDao.wrapRow(it) }
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
            // 대상당 알림이 여럿인 타입은 이 경로 금지
            .limit(1)
            .firstOrNull()
            ?: return false

        if (!notification.isRead) {
            notification.isRead = true
            notification.readAt = nowUtc()
        }

        return true
    }

    /** 수신자의 안읽은 알림 전부 읽음 처리 */
    override suspend fun markAllAsRead(userId: Int): Int {
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

        return updated
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

    /** metadata 일치 최신 알림 조회 */
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
        cursor: PageCursor?,
        limit: Int
    ): List<NotificationDao> {
        var query = (NotificationTable.recipientId eq userId) and (NotificationTable.isActive eq true)

        if (isRead != null) {
            query = query and (NotificationTable.isRead eq isRead)
        }
        if (cursor != null) {
            query = query and afterNewest(NotificationTable.createdAt, NotificationTable.id, cursor)
        }

        return NotificationDao.find { query }
            .orderBy(NotificationTable.createdAt to SortOrder.DESC, NotificationTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .toList()
    }

    /** 수신자의 안읽은 알림 수 */
    override suspend fun countUnreadNotifications(userId: Int): Int {
        return countTotalNotifications(userId, isRead = false)
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

    /** 대상이 사라진 알림 일괄 비활성화 */
    override suspend fun deactivateByTarget(type: NotificationType, targetType: String, targetId: Int): Int {
        return NotificationTable.update({
            (NotificationTable.type eq type) and
                    (NotificationTable.targetType eq targetType) and
                    (NotificationTable.targetId eq targetId) and
                    (NotificationTable.isActive eq true)
        }) {
            it[isActive] = false
            it[updatedAt] = nowUtc()
        }
    }
}
