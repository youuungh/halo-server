package com.ninezero.core.database.entities.user

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID

object BlockedUserTable : BaseIntIdTable("blocked_users") {
    val userId = integer("user_id").references(UserTable.id)                // 차단한 사람
    val blockedUserId = integer("blocked_user_id").references(UserTable.id) // 차단당한 사람
    val isActive = bool("is_active").default(true)

    init {
        uniqueIndex(userId, blockedUserId)
    }
}

class BlockedUserDao(id: EntityID<Int>) : BaseIntEntity(id, BlockedUserTable) {
    companion object : BaseIntEntityClass<BlockedUserDao>(BlockedUserTable)

    var userId by BlockedUserTable.userId
    var blockedUserId by BlockedUserTable.blockedUserId
    var isActive by BlockedUserTable.isActive
}
