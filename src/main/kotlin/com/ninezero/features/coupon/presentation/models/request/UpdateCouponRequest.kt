package com.ninezero.features.coupon.presentation.models.request

import com.ninezero.core.common.config.CouponStatus
import kotlinx.serialization.Serializable

@Serializable
data class UpdateCouponRequest(
    val name: String? = null,
    val description: String? = null,
    val minOrderAmount: String? = null,
    val maxDiscountAmount: String? = null,
    val totalQuantity: Int? = null,
    val maxUseCount: Int? = null,
    val status: CouponStatus? = null,
    val startDate: String? = null,
    val endDate: String? = null
)
