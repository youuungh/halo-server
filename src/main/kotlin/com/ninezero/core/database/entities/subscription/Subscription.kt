package com.ninezero.core.database.entities.subscription

import com.ninezero.core.common.config.SubscriptionStatus
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object SubscriptionTable : BaseIntIdTable("subscriptions") {
    val userId = integer("user_id").references(UserTable.id)
    val creatorId = integer("creator_id").references(UserTable.id)
    val planId = integer("plan_id").references(SubscriptionPlanTable.id)
    val status = enumerationByName<SubscriptionStatus>("status", 50).default(SubscriptionStatus.ACTIVE)
    val startedAt = datetime("started_at")
    val expiresAt = datetime("expires_at")
    val cancelledAt = datetime("cancelled_at").nullable()
    val autoRenew = bool("auto_renew").default(true)

    init {
        index(false, userId, creatorId, status)     // 접근 판정 조회용
        index(false, creatorId, status)             // 크리에이터별 구독자 목록/카운트 조회용
        index(false, status, expiresAt)             // 만료 구독 스케줄러 조회용
    }
}

class SubscriptionDao(id: EntityID<Int>) : BaseIntEntity(id, SubscriptionTable) {
    companion object : BaseIntEntityClass<SubscriptionDao>(SubscriptionTable)

    var userId by SubscriptionTable.userId
    var creatorId by SubscriptionTable.creatorId
    var planId by SubscriptionTable.planId
    var status by SubscriptionTable.status
    var startedAt by SubscriptionTable.startedAt
    var expiresAt by SubscriptionTable.expiresAt
    var cancelledAt by SubscriptionTable.cancelledAt
    var autoRenew by SubscriptionTable.autoRenew
}
