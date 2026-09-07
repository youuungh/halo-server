package com.ninezero.features.chat.data

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.core.common.util.encodeToMediaAttachments
import com.ninezero.core.common.util.ilike
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.chat.ChatRoomDao
import com.ninezero.core.database.entities.chat.MessageDao
import com.ninezero.core.database.entities.chat.MessageTable
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.update

class MessageRepositoryImpl : MessageRepository {

    /** 메시지 생성 */
    override suspend fun createMessage(
        roomId: Int,
        senderId: Int,
        content: String?,
        chatMessageType: ChatMessageType,
        mediaAttachments: List<String>?,
        mediaThumbnails: List<String>?,
        postId: Int?,
        productId: Int?,
        thumbnailUrl: String?,
        duration: Long?,
        mediaWidth: Int?,
        mediaHeight: Int?,
        fileSize: Long?,
        fileName: String?
    ): MessageDao {
        return MessageDao.new {
            this.roomId = roomId
            this.senderId = senderId
            this.chatMessageType = chatMessageType
            this.content = content
            this.mediaAttachments = mediaAttachments?.encodeToMediaAttachments()
            this.mediaThumbnails = mediaThumbnails?.encodeToMediaAttachments()
            this.thumbnailUrl = thumbnailUrl
            this.duration = duration
            this.mediaWidth = mediaWidth
            this.mediaHeight = mediaHeight
            this.fileSize = fileSize
            this.fileName = fileName
            this.productId = productId
            this.postId = postId
            this.isRead = false
            this.readAt = null
            this.isDeleted = false
            this.deletedAt = null
        }
    }

    /** 메시지 삭제 */
    override suspend fun deleteMessage(messageId: Int): Boolean {
        val message = MessageDao.findById(messageId) ?: return false

        message.isDeleted = true
        message.deletedAt = nowUtc()

        return true
    }

    /** 메시지 조회 */
    override suspend fun findMessageById(messageId: Int): MessageDao? {
        return MessageDao.findById(messageId)
    }

    /** 방 메시지 목록 조회 */
    override suspend fun findMessagesByRoomId(
        roomId: Int,
        limit: Int,
        beforeMessageId: Int?,
        clearedAt: LocalDateTime?
    ): List<MessageDao> {
        val query = MessageDao.find {
            // 삭제 메시지도 포함
            var condition: Op<Boolean> = (MessageTable.roomId eq roomId)
            if (beforeMessageId != null) {
                condition = condition and (MessageTable.id less beforeMessageId)
            }
            if (clearedAt != null) {
                condition = condition and (MessageTable.createdAt greater clearedAt)  // clearedAt 이후만
            }
            condition
        }

        return query
            .orderBy(MessageTable.createdAt to SortOrder.DESC, MessageTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .toList()
            .reversed()  // 오래된 순으로 반환
    }

    /** 메시지 검색 */
    override suspend fun searchMessages(
        roomId: Int,
        query: String,
        page: Int,
        limit: Int,
        clearedAt: LocalDateTime?
    ): List<MessageDao> {
        val searchPattern = "%${query}%"

        return MessageDao.find {
            var condition = (MessageTable.roomId eq roomId) and
                    (MessageTable.isDeleted eq false) and
                    (MessageTable.content ilike searchPattern)
            if (clearedAt != null) {
                condition = condition and (MessageTable.createdAt greater clearedAt)  // clearedAt 이후만
            }
            condition
        }
            .orderBy(MessageTable.createdAt to SortOrder.DESC, MessageTable.id to SortOrder.DESC)
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 상대 메시지 일괄 읽음 처리 */
    override suspend fun markAllRead(roomId: Int, receiverId: Int): MessageReadInfo {
        val now = nowUtc()
        val room = ChatRoomDao.findById(roomId)
            ?: return MessageReadInfo(
                messageIds = emptyList(),
                senderId = null,
                lastReadMessageId = null
            )

        val unreadMessages = MessageDao.find {
            (MessageTable.roomId eq roomId) and
                    (MessageTable.senderId neq receiverId) and
                    (MessageTable.isRead eq false) and
                    (MessageTable.isDeleted eq false)
        }.toList()

        if (unreadMessages.isNotEmpty()) {
            val latestReadMessageId = unreadMessages.maxOf { it.id.value }

            val senderId = unreadMessages.first().senderId
            val messageIds = unreadMessages.map { it.id.value }

            MessageTable.update({
                (MessageTable.roomId eq roomId) and
                        (MessageTable.senderId neq receiverId) and
                        (MessageTable.isRead eq false) and
                        (MessageTable.isDeleted eq false)
            }) {
                it[isRead] = true
                it[readAt] = now
                it[updatedAt] = now
            }

            when (receiverId) {
                room.user1Id -> room.user1LastReadMessageId = latestReadMessageId
                room.user2Id -> room.user2LastReadMessageId = latestReadMessageId
            }

            return MessageReadInfo(
                messageIds = messageIds,
                senderId = senderId,
                lastReadMessageId = latestReadMessageId
            )
        }

        val currentLastReadMessageId = when (receiverId) {
            room.user1Id -> room.user1LastReadMessageId
            room.user2Id -> room.user2LastReadMessageId
            else -> null
        }

        return MessageReadInfo(
            messageIds = emptyList(),
            senderId = null,
            lastReadMessageId = currentLastReadMessageId
        )
    }

    /** 검색 메시지 수 */
    override suspend fun countSearchMessages(roomId: Int, query: String, clearedAt: LocalDateTime?): Int {
        val searchPattern = "%${query}%"
        return MessageDao.find {
            var condition = (MessageTable.roomId eq roomId) and
                    (MessageTable.isDeleted eq false) and
                    (MessageTable.content ilike searchPattern)
            if (clearedAt != null) {
                condition = condition and (MessageTable.createdAt greater clearedAt)
            }
            condition
        }.count().toInt()
    }
}
