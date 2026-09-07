package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CreatorSalesSummaryResponse(
    val thisMonthRevenue: String,       // 이번 달 매출
    val thisMonthNetRevenue: String,    // 이번 달 순매출
    val lastMonthRevenue: String,       // 지난달 매출
    val cumulativeRevenue: String,      // 누적 매출
    val pendingCount: Int,              // 결제완료·미확정
    val confirmedCount: Int,            // 확인·배송준비
    val shippingCount: Int,             // 배송중
    val deliveredCount: Int,            // 배송완료
    val cancelledCount: Int             // 취소
)
