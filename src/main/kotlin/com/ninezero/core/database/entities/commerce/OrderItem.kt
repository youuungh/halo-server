package com.ninezero.core.database.entities.commerce

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID

object OrderItemTable : BaseIntIdTable("order_items") {
    val orderId = integer("order_id").references(OrderTable.id).index()
    val productId = integer("product_id").references(ProductTable.id).index()
    val quantity = integer("quantity")
    val price = decimal("price", 10, 2)
    val originalPrice = decimal("original_price", 10, 2).nullable()
}

class OrderItemDao(id: EntityID<Int>) : BaseIntEntity(id, OrderItemTable) {
    companion object : BaseIntEntityClass<OrderItemDao>(OrderItemTable)

    var orderId by OrderItemTable.orderId
    var productId by OrderItemTable.productId
    var quantity by OrderItemTable.quantity
    var price by OrderItemTable.price
    var originalPrice by OrderItemTable.originalPrice
}
