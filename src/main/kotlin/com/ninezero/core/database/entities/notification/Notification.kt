package com.ninezero.core.database.entities.notification

import com.ninezero.core.common.config.NotificationType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object NotificationTable : BaseIntIdTable("notifications") {
    val recipientId = integer("recipient_id").references(UserTable.id)
    val senderId = integer("sender_id").references(UserTable.id).nullable()
    val type = enumerationByName<NotificationType>("type", 50)
    val title = varchar("title", 255)
    val message = text("message")
    val targetType = varchar("target_type", 50).nullable()
    val targetId = integer("target_id").nullable()
    val imageUrl = varchar("image_url", 500).nullable()
    val deepLink = varchar("deep_link", 500).nullable()
    val metadata = text("metadata").nullable()  // JSON 형태로 추가 데이터 저장
    val isRead = bool("is_read").default(false)
    val readAt = datetime("read_at").nullable()
    val isPushSent = bool("is_push_sent").default(false)
    val pushSentAt = datetime("push_sent_at").nullable()
    val isActive = bool("is_active").default(true)

    init {
        index(false, recipientId, isRead, isActive)     // 사용자별 읽지 않은 알림 조회용
        index(false, recipientId, createdAt)            // 사용자별 최신 알림 조회용
        index(false, recipientId, type)                 // 특정 타입 알림 조회용
        index(false, targetType, targetId)              // 대상별 알림 조회용
        index(false, metadata, isActive)                // dedupe 조회용
    }
}

class NotificationDao(id: EntityID<Int>) : BaseIntEntity(id, NotificationTable) {
    companion object : BaseIntEntityClass<NotificationDao>(NotificationTable)

    var recipientId by NotificationTable.recipientId
    var senderId by NotificationTable.senderId
    var type by NotificationTable.type
    var title by NotificationTable.title
    var message by NotificationTable.message
    var targetType by NotificationTable.targetType
    var targetId by NotificationTable.targetId
    var imageUrl by NotificationTable.imageUrl
    var deepLink by NotificationTable.deepLink
    var metadata by NotificationTable.metadata
    var isRead by NotificationTable.isRead
    var readAt by NotificationTable.readAt
    var isPushSent by NotificationTable.isPushSent
    var pushSentAt by NotificationTable.pushSentAt
    var isActive by NotificationTable.isActive
}
