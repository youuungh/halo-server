package com.ninezero.core.database.entities.tag

import com.ninezero.core.database.entities.commerce.ProductTable
import org.jetbrains.exposed.sql.Table

object ProductTagTable : Table("product_tags") {
    val productId = integer("product_id").references(ProductTable.id)
    val tagId = integer("tag_id").references(TagTable.id)
    val isSectionTag = bool("is_section_tag").default(false)

    override val primaryKey = PrimaryKey(productId, tagId)

    init {
        index(false, productId)             // 상품별 태그 조회 최적화
        index(false, tagId, isSectionTag)   // 태그별 상품 조회 최적화
    }
}
