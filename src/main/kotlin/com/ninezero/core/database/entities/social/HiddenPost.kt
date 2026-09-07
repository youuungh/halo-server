package com.ninezero.core.database.entities.social

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object HiddenPostTable : BaseIntIdTable("hidden_posts") {
    val userId = integer("user_id").references(UserTable.id)
    val postId = integer("post_id").references(PostTable.id)
    val isActive = bool("is_active").default(true)

    init {
        uniqueIndex(userId, postId)
    }
}

class HiddenPostDao(id: EntityID<Int>) : BaseIntEntity(id, HiddenPostTable) {
    companion object : BaseIntEntityClass<HiddenPostDao>(HiddenPostTable)

    var userId by HiddenPostTable.userId
    var postId by HiddenPostTable.postId
    var isActive by HiddenPostTable.isActive
}
