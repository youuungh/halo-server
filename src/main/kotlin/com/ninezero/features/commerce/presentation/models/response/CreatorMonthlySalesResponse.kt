package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CreatorMonthlySalesResponse(
    val month: String,  // "YYYY-MM", 선택한 월 기준
    val grossRevenue: String,  // 정수 문자열
    val refundAmount: String,
    val netRevenue: String,
    val orderCount: Int,
    val quantitySold: Int,
    val avgOrderValue: String,
    val shippingFees: String,
    val couponDiscounts: String,
    val statusBreakdown: SalesStatusBreakdownResponse,
    val monthlyTrend: List<MonthRevenueResponse>,  // 최근 6개월
    val bestSellers: List<BestSellerResponse>
)
