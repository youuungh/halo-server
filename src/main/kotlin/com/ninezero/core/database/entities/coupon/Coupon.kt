package com.ninezero.core.database.entities.coupon

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponStatus
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import java.math.BigDecimal
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object CouponTable : BaseIntIdTable("coupons") {
    val creatorId = integer("creator_id")
    val code = varchar("code", 20).uniqueIndex()
    val name = varchar("name", 100)
    val description = varchar("description", 500).nullable()
    val type = enumerationByName<CouponType>("type", 50)
    val discountTarget = enumerationByName<CouponDiscountTarget>("discount_target", 50)
    val discountValue = decimal("discount_value", 10, 2)
    val minOrderAmount = decimal("min_order_amount", 10, 2).default(BigDecimal.ZERO)
    val maxDiscountAmount = decimal("max_discount_amount", 10, 2).nullable()
    val totalQuantity = integer("total_quantity")
    val issuedQuantity = integer("issued_quantity").default(0)
    val maxUseCount = integer("max_use_count").default(1)   // 사용자당 사용 횟수 제한
    val targetIds = varchar("target_ids", 500).nullable()       // 적용 대상
    val status = enumerationByName<CouponStatus>("status", 50).default(CouponStatus.ACTIVE)
    val startDate = datetime("start_date")
    val endDate = datetime("end_date")

    init {
        index(false, status, endDate)       // 유효 쿠폰 조회 최적화
        index(false, creatorId, status)     // 크리에이터별 쿠폰 조회 최적화
    }
}

class CouponDao(id: EntityID<Int>) : BaseIntEntity(id, CouponTable) {
    companion object : BaseIntEntityClass<CouponDao>(CouponTable)

    var creatorId by CouponTable.creatorId
    var code by CouponTable.code
    var name by CouponTable.name
    var description by CouponTable.description
    var type by CouponTable.type
    var discountTarget by CouponTable.discountTarget
    var discountValue by CouponTable.discountValue
    var minOrderAmount by CouponTable.minOrderAmount
    var maxDiscountAmount by CouponTable.maxDiscountAmount
    var totalQuantity by CouponTable.totalQuantity
    var issuedQuantity by CouponTable.issuedQuantity
    var maxUseCount by CouponTable.maxUseCount
    var targetIds by CouponTable.targetIds
    var status by CouponTable.status
    var startDate by CouponTable.startDate
    var endDate by CouponTable.endDate
}
