package com.ninezero.features.chat.data

import com.ninezero.core.common.config.Constants
import com.ninezero.core.database.entities.chat.ChatRoomDao

interface ChatRepository {

    // 채팅방 생성/수정
    suspend fun getOrCreateChatRoom(userId1: Int, userId2: Int): ChatRoomDao
    suspend fun updateChatRoom(
        roomId: Int,
        lastMessageId: Int?,
        lastMessagePreview: String?,
        senderId: Int
    ): Boolean
    suspend fun toggleArchive(roomId: Int, userId: Int, archived: Boolean): Boolean
    suspend fun clearChatRoom(roomId: Int, userId: Int): Boolean

    // 채팅방 조회
    suspend fun findChatRoomById(roomId: Int): ChatRoomDao?
    suspend fun findChatRoomsByUserId(
        userId: Int,
        includeArchived: Boolean = false,
        page: Int,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): List<ChatRoomDao>

    suspend fun findChatRoomBetweenUsers(userId1: Int, userId2: Int): ChatRoomDao?

    // 안읽음 수 수정
    suspend fun incrementUnreadCount(roomId: Int, receiverId: Int): Boolean
    suspend fun resetUnreadCount(roomId: Int, userId: Int): Boolean

    // 카운트
    suspend fun countChatRoomsByUserId(userId: Int, includeArchived: Boolean = false): Int
    suspend fun findRoomsWithUnread(userId: Int): Map<Int, Int>
}
