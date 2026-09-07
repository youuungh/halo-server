package com.ninezero.features.commerce.presentation.models.response

import com.ninezero.core.common.config.ProductStatus
import com.ninezero.features.commerce.presentation.models.DetailContentBlock
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.features.tag.presentation.models.response.TagResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class ProductResponse(
    val id: Int,
    val creator: UserSummaryResponse,
    val name: String,
    val description: String,
    val price: String,
    val originalPrice: String?,
    val discountRate: Int?,
    // 타임딜 override
    val dealPrice: String? = null,
    val dealStartAt: LocalDateTime? = null,
    val dealEndAt: LocalDateTime? = null,
    val isDealActive: Boolean = false,
    // prefill용 원본값
    val basePrice: String,
    val baseOriginalPrice: String? = null,
    val stock: Int,
    val categoryId: Int?,
    val brandName: String?,
    val imageUrls: List<String>,
    val detailContent: List<DetailContentBlock> = emptyList(),
    val tags: List<String>,
    val sectionTag: TagResponse? = null,
    val status: ProductStatus,
    val viewCount: Int,
    val likeCount: Int,
    val salesCount: Int,
    val rating: String,
    val reviewCount: Int,
    val isActive: Boolean,
    val shippingFee: String,
    val requiredTier: SubscriptionPlanTier,
    val canAccess: Boolean?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
