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

object OrderShipmentTable : BaseIntIdTable("order_shipments") {
    val orderId = integer("order_id").references(OrderTable.id)
    val creatorId = integer("creator_id").references(UserTable.id)
    val status = enumerationByName<OrderStatus>("status", 50).default(OrderStatus.PENDING)
    val shippingFee = decimal("shipping_fee", 10, 2).default(BigDecimal.ZERO)
    val couponDiscount = decimal("coupon_discount", 10, 2).default(BigDecimal.ZERO)
    val trackingNumber = varchar("tracking_number", 100).nullable()
    val carrier = varchar("carrier", 50).nullable()
    val deliverySubscriptionId = varchar("delivery_subscription_id", 120).nullable()
    val shippingStatus = enumerationByName<ShippingStatus>("shipping_status", 50).default(ShippingStatus.PREPARING)
    val estimatedDeliveryDate = datetime("estimated_delivery_date").nullable()
    val actualDeliveryDate = datetime("actual_delivery_date").nullable()
    val refundReason = text("refund_reason").nullable()
    val refundAmount = decimal("refund_amount", 10, 2).nullable()
    val refundCompletedAt = datetime("refund_completed_at").nullable()

    init {
        uniqueIndex(orderId, creatorId)
        index(false, creatorId, status, createdAt)
        index(false, creatorId, shippingStatus, createdAt)
    }
}

class OrderShipmentDao(id: EntityID<Int>) : BaseIntEntity(id, OrderShipmentTable) {
    companion object : BaseIntEntityClass<OrderShipmentDao>(OrderShipmentTable)

    var orderId by OrderShipmentTable.orderId
    var creatorId by OrderShipmentTable.creatorId
    var status by OrderShipmentTable.status
    var shippingFee by OrderShipmentTable.shippingFee
    var couponDiscount by OrderShipmentTable.couponDiscount
    var trackingNumber by OrderShipmentTable.trackingNumber
    var carrier by OrderShipmentTable.carrier
    var deliverySubscriptionId by OrderShipmentTable.deliverySubscriptionId
    var shippingStatus by OrderShipmentTable.shippingStatus
    var estimatedDeliveryDate by OrderShipmentTable.estimatedDeliveryDate
    var actualDeliveryDate by OrderShipmentTable.actualDeliveryDate
    var refundReason by OrderShipmentTable.refundReason
    var refundAmount by OrderShipmentTable.refundAmount
    var refundCompletedAt by OrderShipmentTable.refundCompletedAt
}

