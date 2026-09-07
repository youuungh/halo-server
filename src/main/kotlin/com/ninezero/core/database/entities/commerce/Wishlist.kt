package com.ninezero.core.database.entities.commerce

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object WishlistTable : BaseIntIdTable("wishlists") {
    val userId = integer("user_id").references(UserTable.id)
    val productId = integer("product_id").references(ProductTable.id)
    val isActive = bool("is_active").default(true)

    init {
        uniqueIndex(userId, productId)
    }
}

class WishlistDao(id: EntityID<Int>) : BaseIntEntity(id, WishlistTable) {
    companion object : BaseIntEntityClass<WishlistDao>(WishlistTable)

    var userId by WishlistTable.userId
    var productId by WishlistTable.productId
    var isActive by WishlistTable.isActive
}
