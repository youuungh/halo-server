package com.ninezero.core.database.entities.chat

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object ChatRoomTable : BaseIntIdTable("chat_rooms") {
    val user1Id = integer("user1_id").references(UserTable.id)
    val user2Id = integer("user2_id").references(UserTable.id)
    val lastMessageId = integer("last_message_id").nullable()
    val lastMessageAt = datetime("last_message_at").nullable()
    val lastMessagePreview = text("last_message_preview").nullable()
    val user1LastReadMessageId = integer("user1_last_read_message_id").nullable()
    val user2LastReadMessageId = integer("user2_last_read_message_id").nullable()
    val user1UnreadCount = integer("user1_unread_count").default(0)
    val user2UnreadCount = integer("user2_unread_count").default(0)
    val user1Archived = bool("user1_archived").default(false)
    val user2Archived = bool("user2_archived").default(false)
    val user1ClearedAt = datetime("user1_cleared_at").nullable()
    val user2ClearedAt = datetime("user2_cleared_at").nullable()

    init {
        uniqueIndex(user1Id, user2Id)  // user1Id < user2Id 고정

        index(false, user1Id, updatedAt)
        index(false, user2Id, updatedAt)
    }
}

class ChatRoomDao(id: EntityID<Int>) : BaseIntEntity(id, ChatRoomTable) {
    companion object : BaseIntEntityClass<ChatRoomDao>(ChatRoomTable)

    var user1Id by ChatRoomTable.user1Id
    var user2Id by ChatRoomTable.user2Id
    var lastMessageId by ChatRoomTable.lastMessageId
    var lastMessageAt by ChatRoomTable.lastMessageAt
    var lastMessagePreview by ChatRoomTable.lastMessagePreview
    var user1LastReadMessageId by ChatRoomTable.user1LastReadMessageId
    var user2LastReadMessageId by ChatRoomTable.user2LastReadMessageId
    var user1UnreadCount by ChatRoomTable.user1UnreadCount
    var user2UnreadCount by ChatRoomTable.user2UnreadCount
    var user1Archived by ChatRoomTable.user1Archived
    var user2Archived by ChatRoomTable.user2Archived
    var user1ClearedAt by ChatRoomTable.user1ClearedAt
    var user2ClearedAt by ChatRoomTable.user2ClearedAt
}
