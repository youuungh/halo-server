package com.ninezero.core.database.entities.chat

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.commerce.ProductTable
import com.ninezero.core.database.entities.social.PostTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object MessageTable : BaseIntIdTable("messages") {
    val roomId = integer("room_id").references(ChatRoomTable.id)
    val senderId = integer("sender_id").references(UserTable.id)
    val chatMessageType = enumerationByName<ChatMessageType>("chat_message_type", 50).default(ChatMessageType.TEXT)
    val content = text("content").nullable()
    val mediaAttachments = text("media_urls").nullable()
    // 목록용 썸네일
    val mediaThumbnails = text("media_thumbnail_urls").nullable()
    val thumbnailUrl = varchar("thumbnail_url", 500).nullable()
    val duration = long("duration").nullable()
    // 로드 전 비율 예약용
    val mediaWidth = integer("media_width").nullable()
    val mediaHeight = integer("media_height").nullable()
    val fileSize = long("file_size").nullable()
    val fileName = varchar("file_name", 255).nullable()
    val postId = integer("post_id").references(PostTable.id).nullable()
    val productId = integer("product_id").references(ProductTable.id).nullable()
    val isRead = bool("is_read").default(false)
    val readAt = datetime("read_at").nullable()
    val isDeleted = bool("is_deleted").default(false)
    val deletedAt = datetime("deleted_at").nullable()

    init {
        index(false, roomId, createdAt)     // 채팅방별 메시지 조회용 인덱스
        index(false, roomId, isRead)        // 읽지 않은 메시지 조회용
        index(false, senderId, createdAt)   // 발신자별 메시지 조회용
    }
}

class MessageDao(id: EntityID<Int>) : BaseIntEntity(id, MessageTable) {
    companion object : BaseIntEntityClass<MessageDao>(MessageTable)

    var roomId by MessageTable.roomId
    var senderId by MessageTable.senderId
    var chatMessageType by MessageTable.chatMessageType
    var content by MessageTable.content
    var mediaAttachments by MessageTable.mediaAttachments
    var mediaThumbnails by MessageTable.mediaThumbnails
    var thumbnailUrl by MessageTable.thumbnailUrl
    var duration by MessageTable.duration
    var mediaWidth by MessageTable.mediaWidth
    var mediaHeight by MessageTable.mediaHeight
    var fileSize by MessageTable.fileSize
    var fileName by MessageTable.fileName
    var productId by MessageTable.productId
    var postId by MessageTable.postId
    var isRead by MessageTable.isRead
    var readAt by MessageTable.readAt
    var isDeleted by MessageTable.isDeleted
    var deletedAt by MessageTable.deletedAt
}
