package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CreatorSalesSummaryResponse(
    val thisMonthRevenue: String,
    val thisMonthNetRevenue: String,
    val lastMonthRevenue: String,
    val cumulativeRevenue: String,
    val pendingCount: Int,  // 결제완료·미확정
    val confirmedCount: Int,  // 확인·배송준비
    val shippingCount: Int,
    val deliveredCount: Int,
    val cancelledCount: Int
)
