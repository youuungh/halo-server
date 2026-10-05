package com.ninezero.core.database.entities.notification

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object NotificationPreferenceTable : BaseIntIdTable("notification_preferences") {
    val userId = integer("user_id").references(UserTable.id).uniqueIndex()
    val socialEnabled = bool("social_enabled").default(true)
    val creatorActivityEnabled = bool("creator_activity_enabled").default(true)
    val commerceEnabled = bool("commerce_enabled").default(true)
    val chatEnabled = bool("chat_enabled").default(true)
    val systemEnabled = bool("system_enabled").default(true)
}

class NotificationPreferenceDao(id: EntityID<Int>) : BaseIntEntity(id, NotificationPreferenceTable) {
    companion object : BaseIntEntityClass<NotificationPreferenceDao>(NotificationPreferenceTable)

    var userId by NotificationPreferenceTable.userId
    var socialEnabled by NotificationPreferenceTable.socialEnabled
    var creatorActivityEnabled by NotificationPreferenceTable.creatorActivityEnabled
    var commerceEnabled by NotificationPreferenceTable.commerceEnabled
    var chatEnabled by NotificationPreferenceTable.chatEnabled
    var systemEnabled by NotificationPreferenceTable.systemEnabled
}
