package com.ninezero.core.database.entities.tag

import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object TagTable : BaseIntIdTable("tags") {
    val creatorId = integer("creator_id").references(UserTable.id)
    val name = varchar("name", 50)
    val isSectionEnabled = bool("is_section_enabled").default(false)
    val targetType = enumerationByName<TagTargetType>("target_type", 50)

    init {
        index(false, creatorId, targetType)
        index(false, creatorId, targetType, isSectionEnabled)   // 섹션 가능한 태그 조회 최적화
        uniqueIndex(creatorId, name, targetType)
    }
}

class TagDao(id: EntityID<Int>) : BaseIntEntity(id, TagTable) {
    companion object : BaseIntEntityClass<TagDao>(TagTable)

    var creatorId by TagTable.creatorId
    var name by TagTable.name
    var isSectionEnabled by TagTable.isSectionEnabled
    var targetType by TagTable.targetType
}
