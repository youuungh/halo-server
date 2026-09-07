package com.ninezero.features.coupon.presentation.models.response

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponStatus
import com.ninezero.core.common.config.CouponType
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class CouponResponse(
    val id: Int,
    val creatorId: Int,
    val creatorName: String? = null,
    val code: String,
    val name: String,
    val description: String?,
    val type: CouponType,
    val discountTarget: CouponDiscountTarget,
    val discountValue: String,
    val minOrderAmount: String,
    val maxDiscountAmount: String?,
    val totalQuantity: Int,
    val issuedQuantity: Int,
    val remainingQuantity: Int,
    val maxUseCount: Int,
    val targetIds: List<Int>?,
    val status: CouponStatus,
    val startDate: LocalDateTime,
    val endDate: LocalDateTime,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
