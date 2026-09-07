package com.ninezero.features.chat.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.UserBlockedException
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.features.chat.data.ChatRepository
import com.ninezero.features.chat.presentation.models.response.ChatRoomListResponse
import com.ninezero.features.chat.presentation.models.response.ChatRoomResponse
import com.ninezero.features.chat.presentation.models.response.ChatUnreadCountResponse
import com.ninezero.features.chat.toChatRoomResponse
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse
import io.ktor.server.plugins.*

class ChatService(
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val blockedUserRepository: BlockedUserRepository
) {

    suspend fun findChatRoomWithUser(currentUserId: Int, otherUserId: Int): ChatRoomResponse? {
        if (currentUserId == otherUserId) {  // 차단 관계여도 조회는 허용
            throw BadRequestException(Errors.Chat.CANNOT_CHAT_WITH_SELF)
        }

        return query {
            val otherUser = userRepository.findUserById(otherUserId)
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            val chatRoom = chatRepository.findChatRoomBetweenUsers(currentUserId, otherUserId)
                ?: return@query null

            val otherUserSummary = otherUser.toSummaryResponse()
            chatRoom.toChatRoomResponse(otherUserSummary, currentUserId)
        }
    }

    suspend fun getOrCreateChatRoom(currentUserId: Int, otherUserId: Int): ChatRoomResponse {
        if (currentUserId == otherUserId) {
            throw BadRequestException(Errors.Chat.CANNOT_CHAT_WITH_SELF)
        }

        return query {
            if (blockedUserRepository.isBlockedEither(currentUserId, otherUserId)) {
                throw UserBlockedException(Errors.User.Block.USER_BLOCKED)
            }

            val otherUser = userRepository.findUserById(otherUserId)
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            val chatRoom = chatRepository.getOrCreateChatRoom(currentUserId, otherUserId)

            val otherUserSummary = otherUser.toSummaryResponse()
            chatRoom.toChatRoomResponse(otherUserSummary, currentUserId)
        }
    }

    suspend fun getMyChatRooms(
        userId: Int,
        includeArchived: Boolean = false,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): ChatRoomListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val roomList = chatRepository.findChatRoomsByUserId(userId, includeArchived, validPage, validLimit)
            val totalCount = chatRepository.countChatRoomsByUserId(userId, includeArchived)

            if (roomList.isEmpty()) {
                val pagination = PaginationInfo(validPage, validLimit, totalCount)
                return@query createPagedResponse(emptyList(), pagination)
            }

            val otherUserIds = roomList.map { room ->
                if (room.user1Id == userId) room.user2Id else room.user1Id
            }.distinct()

            val users = userRepository.findUsersByIds(otherUserIds)
            val usersMap = users.associateBy { it.id.value }

            val roomResponses = roomList.mapNotNull { room ->
                val otherUserId = if (room.user1Id == userId) room.user2Id else room.user1Id
                val otherUser = usersMap[otherUserId]?.toSummaryResponse() ?: return@mapNotNull null  // 상대 없으면 목록에서 제외
                room.toChatRoomResponse(otherUser, userId)
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(roomResponses, pagination)
        }
    }

    suspend fun getChatRoomById(roomId: Int, currentUserId: Int): ChatRoomResponse {
        return query {
            val chatRoom = requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Chat.CHAT_PERMISSION_DENIED)

            val otherUserId = if (chatRoom.user1Id == currentUserId) chatRoom.user2Id else chatRoom.user1Id
            val otherUser = userRepository.findUserById(otherUserId)
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            val otherUserSummary = otherUser.toSummaryResponse()
            chatRoom.toChatRoomResponse(otherUserSummary, currentUserId)
        }
    }

    suspend fun getUnreadCount(userId: Int): ChatUnreadCountResponse {
        return query {
            val roomUnreadCounts = chatRepository.findRoomsWithUnread(userId)
            val totalUnread = roomUnreadCounts.values.sum()

            ChatUnreadCountResponse(
                totalUnreadCount = totalUnread,
                roomUnreadCounts = roomUnreadCounts
            )
        }
    }

    suspend fun toggleArchive(roomId: Int, currentUserId: Int, archived: Boolean) {
        query {
            requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Chat.CHAT_PERMISSION_DENIED)
            chatRepository.toggleArchive(roomId, currentUserId, archived)
        }
    }

    suspend fun clearChatRoom(roomId: Int, currentUserId: Int) {
        query {
            requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Chat.CHAT_PERMISSION_DENIED)
            chatRepository.clearChatRoom(roomId, currentUserId)  // 본인 기록만 가림, 새 메시지로 복구
        }
    }
}
