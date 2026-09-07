package com.ninezero.features.chat.data

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.database.entities.chat.MessageDao
import kotlinx.datetime.LocalDateTime

data class MessageReadInfo(
    val messageIds: List<Int>,
    val senderId: Int?,
    val lastReadMessageId: Int?
)

interface MessageRepository {

    // 메시지 생성/삭제
    suspend fun createMessage(
        roomId: Int,
        senderId: Int,
        content: String?,
        chatMessageType: ChatMessageType = ChatMessageType.TEXT,
        mediaAttachments: List<String>? = null,
        mediaThumbnails: List<String>? = null,
        postId: Int? = null,
        productId: Int? = null,
        thumbnailUrl: String? = null,
        duration: Long? = null,
        mediaWidth: Int? = null,
        mediaHeight: Int? = null,
        fileSize: Long? = null,
        fileName: String? = null
    ): MessageDao
    suspend fun deleteMessage(messageId: Int): Boolean

    // 메시지 조회
    suspend fun findMessageById(messageId: Int): MessageDao?
    suspend fun findMessagesByRoomId(
        roomId: Int,
        limit: Int = Constants.Chat.DEFAULT_MESSAGE_LIMIT,
        beforeMessageId: Int? = null,
        clearedAt: LocalDateTime? = null
    ): List<MessageDao>
    suspend fun searchMessages(
        roomId: Int,
        query: String,
        page: Int,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        clearedAt: LocalDateTime? = null
    ): List<MessageDao>

    // 읽음 처리
    suspend fun markAllRead(roomId: Int, receiverId: Int): MessageReadInfo

    // 카운트
    suspend fun countSearchMessages(roomId: Int, query: String, clearedAt: LocalDateTime? = null): Int
}
