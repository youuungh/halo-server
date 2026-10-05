package com.ninezero.features.coupon.presentation.models.response

import com.ninezero.core.common.config.UserCouponStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class UserCouponResponse(
    val id: Int,
    val coupon: CouponResponse,
    val status: UserCouponStatus,
    val useCount: Int,
    val maxUseCount: Int,
    val remainingUseCount: Int,
    val usedAt: LocalDateTime? = null,
    val orderId: Int? = null,
    val claimedAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val daysRemaining: Int
)
