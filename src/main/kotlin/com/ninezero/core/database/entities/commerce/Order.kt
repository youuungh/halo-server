package com.ninezero.core.database.entities.commerce

import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.ShippingStatus
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.math.BigDecimal

object OrderTable : BaseIntIdTable("orders") {
    val userId = integer("user_id").references(UserTable.id)
    val orderNumber = varchar("order_number", 50).uniqueIndex()
    val totalPrice = decimal("total_price", 10, 2)
    val status = enumerationByName<OrderStatus>("status", 50).default(OrderStatus.PENDING)
    val shippingAddress = text("shipping_address")
    val shippingPhone = varchar("shipping_phone", 20)
    val shippingName = varchar("shipping_name", 100)
    val memo = text("memo").nullable()

    // 포인트 및 쿠폰 관련 필드
    val pointsUsed = decimal("points_used", 10, 2).default(BigDecimal.ZERO)
    val couponDiscount = decimal("coupon_discount", 10, 2).default(BigDecimal.ZERO)

    // 배송비
    val shippingFee = decimal("shipping_fee", 10, 2).default(BigDecimal.ZERO)

    // 주문 시점 구독 티어 스냅샷
    val subscriptionTiers = text("subscription_tiers").nullable()

    // 배송 추적 관련 필드
    val trackingNumber = varchar("tracking_number", 100).nullable()
    val carrier = varchar("carrier", 50).nullable()
    val deliverySubscriptionId = varchar("delivery_subscription_id", 120).nullable()
    val shippingStatus = enumerationByName<ShippingStatus>("shipping_status", 50).default(ShippingStatus.PREPARING)
    val estimatedDeliveryDate = datetime("estimated_delivery_date").nullable()
    val actualDeliveryDate = datetime("actual_delivery_date").nullable()

    init {
        index(false, userId, status, createdAt)     // 사용자별 주문 조회 최적화
        index(false, status, createdAt)             // 관리자 주문 관리 최적화
    }
}

class OrderDao(id: EntityID<Int>) : BaseIntEntity(id, OrderTable) {
    companion object : BaseIntEntityClass<OrderDao>(OrderTable)

    var userId by OrderTable.userId
    var orderNumber by OrderTable.orderNumber
    var totalPrice by OrderTable.totalPrice
    var status by OrderTable.status
    var shippingAddress by OrderTable.shippingAddress
    var shippingPhone by OrderTable.shippingPhone
    var shippingName by OrderTable.shippingName
    var memo by OrderTable.memo
    var pointsUsed by OrderTable.pointsUsed
    var couponDiscount by OrderTable.couponDiscount
    var shippingFee by OrderTable.shippingFee
    var subscriptionTiers by OrderTable.subscriptionTiers
    var trackingNumber by OrderTable.trackingNumber
    var carrier by OrderTable.carrier
    var deliverySubscriptionId by OrderTable.deliverySubscriptionId
    var shippingStatus by OrderTable.shippingStatus
    var estimatedDeliveryDate by OrderTable.estimatedDeliveryDate
    var actualDeliveryDate by OrderTable.actualDeliveryDate
}
