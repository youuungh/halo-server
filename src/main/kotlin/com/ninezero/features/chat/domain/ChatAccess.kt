package com.ninezero.features.chat.domain

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.database.entities.chat.ChatRoomDao
import com.ninezero.features.chat.data.ChatRepository

internal suspend fun requireChatRoomParticipant(
    chatRepository: ChatRepository,
    roomId: Int,
    userId: Int,
    errorMessage: String
): ChatRoomDao {
    val room = chatRepository.findChatRoomById(roomId)
        ?: throw NotFoundException(Errors.Chat.CHAT_ROOM_NOT_FOUND)

    if (room.user1Id != userId && room.user2Id != userId) {
        throw ForbiddenException(errorMessage)
    }

    return room
}
