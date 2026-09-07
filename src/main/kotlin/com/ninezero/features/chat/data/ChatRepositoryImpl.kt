package com.ninezero.features.chat.data

import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.chat.ChatRoomDao
import com.ninezero.core.database.entities.chat.ChatRoomTable
import org.jetbrains.exposed.sql.Coalesce
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or

class ChatRepositoryImpl : ChatRepository {

    /** cleared 방 숨김 조건 */
    private fun buildVisibleAfterClearedCondition(userId: Int): Op<Boolean> {
        return ((ChatRoomTable.user1Id eq userId) and (
                (ChatRoomTable.user1ClearedAt eq null) or  // clearedAt null이면 보임
                (ChatRoomTable.lastMessageAt greater ChatRoomTable.user1ClearedAt)  // 새 메시지면 부활
        )) or ((ChatRoomTable.user2Id eq userId) and (
                (ChatRoomTable.user2ClearedAt eq null) or
                (ChatRoomTable.lastMessageAt greater ChatRoomTable.user2ClearedAt)
        ))
    }

    /** 채팅방 목록 공통 조건 */
    private fun buildUserRoomsCondition(userId: Int, includeArchived: Boolean): Op<Boolean> {
        val baseCondition = (ChatRoomTable.user1Id eq userId) or (ChatRoomTable.user2Id eq userId)
        val clearedFilter = buildVisibleAfterClearedCondition(userId)

        return if (includeArchived) {
            baseCondition and clearedFilter
        } else {
            baseCondition and (
                ((ChatRoomTable.user1Id eq userId) and (ChatRoomTable.user1Archived eq false)) or
                ((ChatRoomTable.user2Id eq userId) and (ChatRoomTable.user2Archived eq false))
            ) and clearedFilter
        }
    }

    /** 채팅방 조회 또는 생성 */
    override suspend fun getOrCreateChatRoom(userId1: Int, userId2: Int): ChatRoomDao {
        val (smallerId, largerId) = if (userId1 < userId2) userId1 to userId2 else userId2 to userId1  // 작은 id가 user1

        val existingRoom = ChatRoomDao.find {
            (ChatRoomTable.user1Id eq smallerId) and (ChatRoomTable.user2Id eq largerId)
        }.firstOrNull()

        return existingRoom ?: run {
            ChatRoomDao.new {
                this.user1Id = smallerId
                this.user2Id = largerId
                lastMessageId = null
                lastMessageAt = null
                lastMessagePreview = null
                user1LastReadMessageId = null
                user2LastReadMessageId = null
                user1UnreadCount = 0
                user2UnreadCount = 0
                user1Archived = false
                user2Archived = false
            }
        }
    }

    /** 채팅방 마지막 메시지 갱신 */
    override suspend fun updateChatRoom(
        roomId: Int,
        lastMessageId: Int?,
        lastMessagePreview: String?,
        senderId: Int
    ): Boolean {
        val room = ChatRoomDao.findById(roomId) ?: return false  // senderId 파라미터는 미사용

        room.lastMessageId = lastMessageId
        room.lastMessagePreview = lastMessagePreview
        room.lastMessageAt = nowUtc()

        return true
    }

    /** 본인 쪽 보관 설정 */
    override suspend fun toggleArchive(roomId: Int, userId: Int, archived: Boolean): Boolean {
        val room = ChatRoomDao.findById(roomId) ?: return false

        when (userId) {
            room.user1Id -> room.user1Archived = archived
            room.user2Id -> room.user2Archived = archived
        }

        return true
    }

    /** 본인 쪽만 채팅방 삭제 */
    override suspend fun clearChatRoom(roomId: Int, userId: Int): Boolean {
        val room = ChatRoomDao.findById(roomId) ?: return false
        val now = nowUtc()

        when (userId) {
            room.user1Id -> {
                room.user1ClearedAt = now
                room.user1UnreadCount = 0
            }
            room.user2Id -> {
                room.user2ClearedAt = now
                room.user2UnreadCount = 0
            }
            else -> return false
        }

        return true
    }

    /** 채팅방 조회 */
    override suspend fun findChatRoomById(roomId: Int): ChatRoomDao? {
        return ChatRoomDao.findById(roomId)
    }

    /** 사용자의 채팅방 목록 조회 */
    override suspend fun findChatRoomsByUserId(
        userId: Int,
        includeArchived: Boolean,
        page: Int,
        limit: Int
    ): List<ChatRoomDao> {
        val query = buildUserRoomsCondition(userId, includeArchived)  // includeArchived=false면 archived 가림

        return ChatRoomDao.find { query }
            .orderBy(
                // updatedAt 정렬 키 금지
                // 실제 마지막 메시지 시각 기준 정렬
                Coalesce(ChatRoomTable.lastMessageAt, ChatRoomTable.createdAt) to SortOrder.DESC,
                ChatRoomTable.id to SortOrder.DESC
            )
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 두 사용자 쌍의 채팅방 조회 */
    override suspend fun findChatRoomBetweenUsers(userId1: Int, userId2: Int): ChatRoomDao? {
        val (smallerId, largerId) = if (userId1 < userId2) userId1 to userId2 else userId2 to userId1

        return ChatRoomDao.find {
            (ChatRoomTable.user1Id eq smallerId) and (ChatRoomTable.user2Id eq largerId)
        }.firstOrNull()
    }

    /** receiver 쪽 unreadCount +1 */
    override suspend fun incrementUnreadCount(roomId: Int, receiverId: Int): Boolean {
        val room = ChatRoomDao.findById(roomId) ?: return false

        when (receiverId) {
            room.user1Id -> room.user1UnreadCount += 1
            room.user2Id -> room.user2UnreadCount += 1
        }

        return true
    }

    /** 본인 쪽 unreadCount 0으로 초기화 */
    override suspend fun resetUnreadCount(roomId: Int, userId: Int): Boolean {
        val room = ChatRoomDao.findById(roomId) ?: return false

        when (userId) {
            room.user1Id -> room.user1UnreadCount = 0
            room.user2Id -> room.user2UnreadCount = 0
        }

        return true
    }

    /** 사용자의 채팅방 수 */
    override suspend fun countChatRoomsByUserId(userId: Int, includeArchived: Boolean): Int {
        val query = buildUserRoomsCondition(userId, includeArchived)

        return ChatRoomDao.find { query }.count().toInt()
    }

    /** 안읽음 있는 방의 안읽음 수 조회 */
    override suspend fun findRoomsWithUnread(userId: Int): Map<Int, Int> {
        return ChatRoomDao.find {
            (ChatRoomTable.user1Id eq userId) or (ChatRoomTable.user2Id eq userId)  // archived·cleared 가림 없음
        }.mapNotNull { room ->
            val unread = when (userId) {
                room.user1Id -> room.user1UnreadCount
                room.user2Id -> room.user2UnreadCount
                else -> 0
            }
            if (unread > 0) room.id.value to unread else null
        }.toMap()
    }
}
