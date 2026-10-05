package com.ninezero.features.coupon.presentation.models.response

import com.ninezero.core.common.util.PaginatedResponse
import kotlinx.serialization.Serializable

@Serializable
data class UserCouponListResponse(
    val coupons: PaginatedResponse<UserCouponResponse>,
    val availableCount: Int
)
