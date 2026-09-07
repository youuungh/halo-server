package com.ninezero.core.database.entities.commerce

import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.math.BigDecimal

object ProductTable : BaseIntIdTable("products") {
    val creatorId = integer("creator_id").references(UserTable.id)
    val name = varchar("name", 255)
    val description = text("description")
    val price = decimal("price", 10, 2)
    val originalPrice = decimal("original_price", 10, 2).nullable()
    val stock = integer("stock").default(0)
    val categoryId = integer("category_id").nullable()
    val brandName = varchar("brand_name", 100).nullable()
    val imageUrls = text("image_urls").nullable()
    val detailContent = text("detail_content").nullable()
    val tags = text("tags").nullable()
    val status = enumerationByName<ProductStatus>("status", 50).default(ProductStatus.ACTIVE)
    val viewCount = integer("view_count").default(0)
    val likeCount = integer("like_count").default(0)
    val salesCount = integer("sales_count").default(0)
    val rating = decimal("rating", 2, 1).default(BigDecimal.ZERO)
    val reviewCount = integer("review_count").default(0)
    val isActive = bool("is_active").default(true)
    val requiredTier = enumerationByName<SubscriptionPlanTier>("required_tier", 50).default(SubscriptionPlanTier.FREE)
    val dealPrice = decimal("deal_price", 10, 2).nullable()
    val dealStartAt = datetime("deal_start_at").nullable()
    val dealEndAt = datetime("deal_end_at").nullable()

    init {
        index(false, creatorId, status)     // 판매자별 상품 조회 최적화
        index(false, categoryId, status)    // 카테고리별 상품 조회 최적화
        index(false, status, createdAt)     // 상태별 최신순 조회 최적화
    }
}

class ProductDao(id: EntityID<Int>) : BaseIntEntity(id, ProductTable) {
    companion object : BaseIntEntityClass<ProductDao>(ProductTable)

    var creatorId by ProductTable.creatorId
    var name by ProductTable.name
    var description by ProductTable.description
    var price by ProductTable.price
    var originalPrice by ProductTable.originalPrice
    var stock by ProductTable.stock
    var categoryId by ProductTable.categoryId
    var brandName by ProductTable.brandName
    var imageUrls by ProductTable.imageUrls
    var detailContent by ProductTable.detailContent
    var tags by ProductTable.tags
    var status by ProductTable.status
    var viewCount by ProductTable.viewCount
    var likeCount by ProductTable.likeCount
    var salesCount by ProductTable.salesCount
    var rating by ProductTable.rating
    var reviewCount by ProductTable.reviewCount
    var isActive by ProductTable.isActive
    var requiredTier by ProductTable.requiredTier
    var dealPrice by ProductTable.dealPrice
    var dealStartAt by ProductTable.dealStartAt
    var dealEndAt by ProductTable.dealEndAt
}
