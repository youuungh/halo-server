package com.ninezero.core.database.entities.point

import com.ninezero.core.common.config.PointType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object PointHistoryTable : BaseIntIdTable("point_histories") {
    val userId = integer("user_id").references(UserTable.id)
    val type = enumerationByName<PointType>("type", 50)
    val amount = decimal("amount", 10, 2)
    val balanceBefore = decimal("balance_before", 10, 2)
    val balanceAfter = decimal("balance_after", 10, 2)
    val orderId = integer("order_id").nullable() // 주문 관련 포인트인 경우
    val description = text("description")
    val expiresAt = datetime("expires_at").nullable() // 적립

    init {
        index(false, userId, createdAt)     // 사용자별 내역 조회 최적화
        index(false, orderId)               // 주문별 포인트 조회
    }
}

class PointHistoryDao(id: EntityID<Int>) : BaseIntEntity(id, PointHistoryTable) {
    companion object : BaseIntEntityClass<PointHistoryDao>(PointHistoryTable)

    var userId by PointHistoryTable.userId
    var type by PointHistoryTable.type
    var amount by PointHistoryTable.amount
    var balanceBefore by PointHistoryTable.balanceBefore
    var balanceAfter by PointHistoryTable.balanceAfter
    var orderId by PointHistoryTable.orderId
    var description by PointHistoryTable.description
    var expiresAt by PointHistoryTable.expiresAt
}
