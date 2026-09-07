package com.ninezero.core.database.entities.commerce

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object CartTable : BaseIntIdTable("carts") {
    val userId = integer("user_id").references(UserTable.id)
    val productId = integer("product_id").references(ProductTable.id)
    val quantity = integer("quantity").default(1)

    init {
        uniqueIndex("cart_user_product_idx", userId, productId)
    }
}

class CartDao(id: EntityID<Int>) : BaseIntEntity(id, CartTable) {
    companion object : BaseIntEntityClass<CartDao>(CartTable)

    var userId by CartTable.userId
    var productId by CartTable.productId
    var quantity by CartTable.quantity
}
