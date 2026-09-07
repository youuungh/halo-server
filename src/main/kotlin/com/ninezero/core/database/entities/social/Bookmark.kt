package com.ninezero.core.database.entities.social

import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object BookmarkTable : BaseIntIdTable("bookmarks") {
    val userId = integer("user_id").references(UserTable.id)
    val targetType = enumerationByName<BookmarkTargetType>("target_type", 20)
    val targetId = integer("target_id")
    val isActive = bool("is_active").default(true)

    init {
        uniqueIndex(userId, targetType, targetId)
    }
}

class BookmarkDao(id: EntityID<Int>) : BaseIntEntity(id, BookmarkTable) {
    companion object : BaseIntEntityClass<BookmarkDao>(BookmarkTable)

    var userId by BookmarkTable.userId
    var targetType by BookmarkTable.targetType
    var targetId by BookmarkTable.targetId
    var isActive by BookmarkTable.isActive
}
