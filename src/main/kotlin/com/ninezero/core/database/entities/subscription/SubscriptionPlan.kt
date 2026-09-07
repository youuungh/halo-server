package com.ninezero.core.database.entities.subscription

import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object SubscriptionPlanTable : BaseIntIdTable("subscription_plans") {
    val creatorId = integer("creator_id").references(UserTable.id)
    val name = varchar("name", 50)
    val tier = enumerationByName<SubscriptionPlanTier>("tier", 50)
    val description = text("description")
    val price = decimal("price", 10, 2)
    val benefits = text("benefits")
    val subscriberCount = integer("subscriber_count").default(0)
    val isActive = bool("is_active").default(true)
}

class SubscriptionPlanDao(id: EntityID<Int>) : BaseIntEntity(id, SubscriptionPlanTable) {
    companion object : BaseIntEntityClass<SubscriptionPlanDao>(SubscriptionPlanTable)

    var creatorId by SubscriptionPlanTable.creatorId
    var name by SubscriptionPlanTable.name
    var tier by SubscriptionPlanTable.tier
    var description by SubscriptionPlanTable.description
    var price by SubscriptionPlanTable.price
    var benefits by SubscriptionPlanTable.benefits
    var subscriberCount by SubscriptionPlanTable.subscriberCount
    var isActive by SubscriptionPlanTable.isActive
}
