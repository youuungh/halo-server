package com.ninezero.core.database.entities.user

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ReferenceOption

object UserProfileTable : BaseIntIdTable("user_profiles") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val displayName = varchar("display_name", 100)
    val bio = text("bio").nullable()
    val avatarUrl = varchar("avatar_url", 500).nullable()
    val avatarThumbUrl = varchar("avatar_thumb_url", 500).nullable()
    val location = varchar("location", 100).nullable()
    val website = varchar("website", 200).nullable()
}

class UserProfileDao(id: EntityID<Int>) : BaseIntEntity(id, UserProfileTable) {
    companion object : BaseIntEntityClass<UserProfileDao>(UserProfileTable)

    var userId by UserProfileTable.userId
    var displayName by UserProfileTable.displayName
    var bio by UserProfileTable.bio
    var avatarUrl by UserProfileTable.avatarUrl
    var avatarThumbUrl by UserProfileTable.avatarThumbUrl
    var location by UserProfileTable.location
    var website by UserProfileTable.website
}
