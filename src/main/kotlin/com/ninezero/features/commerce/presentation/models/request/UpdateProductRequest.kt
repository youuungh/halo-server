package com.ninezero.features.commerce.presentation.models.request

import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.features.commerce.presentation.models.DetailContentBlock
import kotlinx.serialization.Serializable

@Serializable
data class UpdateProductRequest(
    val name: String? = null,
    val description: String? = null,
    val price: String? = null,
    val originalPrice: String? = null,
    val stock: Int? = null,
    val categoryId: Int? = null,
    val brandName: String? = null,
    val imageUrls: List<String>? = null,
    val detailContent: List<DetailContentBlock>? = null,
    val tags: List<String>? = null,
    val status: ProductStatus? = null,
    val requiredTier: SubscriptionPlanTier? = null,
    val tagIds: List<Int>? = null,
    val sectionTagId: Int? = null
)
