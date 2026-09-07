package com.ninezero.features.commerce.presentation.models.request

import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.features.commerce.presentation.models.DetailContentBlock
import kotlinx.serialization.Serializable

@Serializable
data class ProductRequest(
    val name: String,
    val description: String,
    val price: String,
    val originalPrice: String? = null,
    val stock: Int,
    val categoryId: Int? = null,
    val brandName: String? = null,
    val imageUrls: List<String>? = null,
    val detailContent: List<DetailContentBlock>? = null,
    val tags: List<String>? = null,
    val requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE,
    val tagIds: List<Int>? = null,
    val sectionTagId: Int? = null
)
