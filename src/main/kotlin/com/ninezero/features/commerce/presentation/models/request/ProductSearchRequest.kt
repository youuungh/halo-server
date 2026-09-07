package com.ninezero.features.commerce.presentation.models.request

import com.ninezero.core.common.config.ProductStatus
import kotlinx.serialization.Serializable

@Serializable
data class ProductSearchRequest(
    val query: String? = null,
    val categoryId: Int? = null,
    val brandName: String? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val status: ProductStatus? = null,
    val tags: List<String>? = null,
    val page: Int = 1,
    val limit: Int = 20
)
