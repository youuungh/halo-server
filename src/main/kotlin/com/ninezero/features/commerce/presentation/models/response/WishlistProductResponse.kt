package com.ninezero.features.commerce.presentation.models.response

import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.config.PurchaseState
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.serialization.Serializable

@Serializable
data class WishlistProductResponse(
    val id: Int,
    val name: String,
    val description: String,
    val price: String,
    val originalPrice: String?,
    val discountRate: Int?,
    val imageUrl: String?,
    val stock: Int,
    val status: ProductStatus,
    val isActive: Boolean,
    val canAccess: Boolean,
    val purchaseState: PurchaseState,
    val rating: String,
    val reviewCount: Int,
    val creator: UserSummaryResponse
)
