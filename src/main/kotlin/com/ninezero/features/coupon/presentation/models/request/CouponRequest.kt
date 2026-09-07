package com.ninezero.features.coupon.presentation.models.request

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponType
import kotlinx.serialization.Serializable

@Serializable
data class CouponRequest(
    val code: String? = null,
    val name: String,
    val description: String? = null,
    val type: CouponType,
    val discountTarget: CouponDiscountTarget,
    val discountValue: String,
    val minOrderAmount: String? = null,
    val maxDiscountAmount: String? = null,
    val totalQuantity: Int,
    val maxUseCount: Int = 1,
    val targetIds: List<Int>? = null,  // 적용 대상 상품/카테고리 ID
    val startDate: String,
    val endDate: String
)
