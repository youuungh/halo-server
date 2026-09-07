package com.ninezero.features.coupon.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class ClaimCouponRequest(
    val code: String
)