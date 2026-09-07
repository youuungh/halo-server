package com.ninezero.core.database.entities.coupon

import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object UserCouponTable : BaseIntIdTable("user_coupons") {
    val userId = integer("user_id")
    val couponId = integer("coupon_id").references(CouponTable.id)
    val status = enumerationByName<UserCouponStatus>("status", 50).default(UserCouponStatus.AVAILABLE)
    val useCount = integer("use_count").default(0)
    val usedAt = datetime("used_at").nullable()
    val orderId = integer("order_id").nullable()
    val claimedAt = datetime("claimed_at")
    val expiresAt = datetime("expires_at")

    init {
        uniqueIndex(userId, couponId)
    }
}

class UserCouponDao(id: EntityID<Int>) : BaseIntEntity(id, UserCouponTable) {
    companion object : BaseIntEntityClass<UserCouponDao>(UserCouponTable)

    var userId by UserCouponTable.userId
    var couponId by UserCouponTable.couponId
    var status by UserCouponTable.status
    var useCount by UserCouponTable.useCount
    var usedAt by UserCouponTable.usedAt
    var orderId by UserCouponTable.orderId
    var claimedAt by UserCouponTable.claimedAt
    var expiresAt by UserCouponTable.expiresAt
}
