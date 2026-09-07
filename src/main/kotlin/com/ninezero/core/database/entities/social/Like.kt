package com.ninezero.core.database.entities.social

import com.ninezero.core.common.config.LikeType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object LikeTable : BaseIntIdTable("likes") {
    val userId = integer("user_id").references(UserTable.id)
    val targetType = enumerationByName<LikeType>("target_type", 50)  // POST, COMMENT
    val targetId = integer("target_id")
    val isActive = bool("is_active").default(true)

    init {
        uniqueIndex(userId, targetType, targetId)
        index(false, targetId, targetType, isActive)    // targetId 조회용 별도 index
    }
}

class LikeDao(id: EntityID<Int>) : BaseIntEntity(id, LikeTable) {
    companion object : BaseIntEntityClass<LikeDao>(LikeTable)

    var userId by LikeTable.userId
    var targetType by LikeTable.targetType
    var targetId by LikeTable.targetId
    var isActive by LikeTable.isActive
}
