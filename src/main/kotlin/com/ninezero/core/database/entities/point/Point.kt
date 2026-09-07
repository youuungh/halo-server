package com.ninezero.core.database.entities.point

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import java.math.BigDecimal

object PointTable : BaseIntIdTable("points") {
    val userId = integer("user_id").references(UserTable.id)
    val balance = decimal("balance", 10, 2).default(BigDecimal.ZERO)
    val totalEarned = decimal("total_earned", 10, 2).default(BigDecimal.ZERO)
    val totalUsed = decimal("total_used", 10, 2).default(BigDecimal.ZERO)
    val totalExpired = decimal("total_expired", 10, 2).default(BigDecimal.ZERO)

    init {
        uniqueIndex(userId)
    }
}

class PointDao(id: EntityID<Int>) : BaseIntEntity(id, PointTable) {
    companion object : BaseIntEntityClass<PointDao>(PointTable)

    var userId by PointTable.userId
    var balance by PointTable.balance
    var totalEarned by PointTable.totalEarned
    var totalUsed by PointTable.totalUsed
    var totalExpired by PointTable.totalExpired
}
