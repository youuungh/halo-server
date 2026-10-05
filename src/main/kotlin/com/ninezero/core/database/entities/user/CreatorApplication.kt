package com.ninezero.core.database.entities.user

import com.ninezero.core.common.config.CreatorApplicationStatus
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object CreatorApplicationTable : BaseIntIdTable("creator_applications") {
    val userId = integer("user_id").references(UserTable.id)
    val reason = text("reason")
    val portfolioUrl = varchar("portfolio_url", 500).nullable()
    val status = enumerationByName<CreatorApplicationStatus>("status", 50).default(CreatorApplicationStatus.PENDING)
    val rejectionReason = text("rejection_reason").nullable()
    val reviewedBy = integer("reviewed_by").references(UserTable.id).nullable()
    val reviewedAt = datetime("reviewed_at").nullable()
}

class CreatorApplicationDao(id: EntityID<Int>) : BaseIntEntity(id, CreatorApplicationTable) {
    companion object : BaseIntEntityClass<CreatorApplicationDao>(CreatorApplicationTable)

    var userId by CreatorApplicationTable.userId
    var reason by CreatorApplicationTable.reason
    var portfolioUrl by CreatorApplicationTable.portfolioUrl
    var status by CreatorApplicationTable.status
    var rejectionReason by CreatorApplicationTable.rejectionReason
    var reviewedBy by CreatorApplicationTable.reviewedBy
    var reviewedAt by CreatorApplicationTable.reviewedAt
}
