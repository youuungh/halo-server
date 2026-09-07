package com.ninezero.core.database.entities.social

import com.ninezero.core.common.config.ReportTargetType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object ReportTable : BaseIntIdTable("reports") {
    val reporterId = integer("reporter_id").references(UserTable.id)
    val targetType = enumerationByName("target_type", 20, ReportTargetType::class)
    val targetId = integer("target_id")

    init {
        uniqueIndex(reporterId, targetType, targetId)
        index(false, targetType, targetId)      // 대상별 신고 수 집계
    }
}

class ReportDao(id: EntityID<Int>) : BaseIntEntity(id, ReportTable) {
    companion object : BaseIntEntityClass<ReportDao>(ReportTable)

    var reporterId by ReportTable.reporterId
    var targetType by ReportTable.targetType
    var targetId by ReportTable.targetId
}
