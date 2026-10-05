package com.ninezero.features.commerce.data

import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.database.entities.commerce.ProductDao

/** 재고 기반 상태 동기화 규칙 */
object ProductStatusRules {
    fun syncByStock(product: ProductDao) {
        if (product.status == ProductStatus.DISCONTINUED) return  // 재고 변경으로 덮지 않음

        if (product.stock <= 0) {
            product.status = ProductStatus.SOLD_OUT
            return
        }

        if (product.status == ProductStatus.SOLD_OUT) {
            product.status = ProductStatus.ACTIVE
        }
    }
}
