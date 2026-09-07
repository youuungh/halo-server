package com.ninezero.core.database.entities.commerce

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object ReviewTable : BaseIntIdTable("reviews") {
    val userId = integer("user_id").references(UserTable.id)
    val productId = integer("product_id").references(ProductTable.id)
    val orderId = integer("order_id").references(OrderTable.id)
    val rating = integer("rating")
    val content = text("content")
    val images = text("images").nullable()
    val isActive = bool("is_active").default(true)

    init {
        uniqueIndex(orderId, productId)
        // productId 선두 조회용 별도 index
        index(false, productId, isActive)
        index(false, userId, isActive)
    }
}

class ReviewDao(id: EntityID<Int>) : BaseIntEntity(id, ReviewTable) {
    companion object : BaseIntEntityClass<ReviewDao>(ReviewTable)

    var userId by ReviewTable.userId
    var productId by ReviewTable.productId
    var orderId by ReviewTable.orderId
    var rating by ReviewTable.rating
    var content by ReviewTable.content
    var images by ReviewTable.images
    var isActive by ReviewTable.isActive
}
