package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CreatorMonthlySalesResponse(
    val month: String,                              // "YYYY-MM", 선택한 월 기준
    val grossRevenue: String,                       // 총 매출, 정수 문자열
    val refundAmount: String,                       // 환불액
    val netRevenue: String,                         // 순매출
    val orderCount: Int,                            // 주문 건수
    val quantitySold: Int,                          // 총 판매 수량
    val avgOrderValue: String,                      // 평균 주문가
    val shippingFees: String,                       // 배송비 합계
    val couponDiscounts: String,                    // 쿠폰 할인 합계
    val statusBreakdown: SalesStatusBreakdownResponse,
    val monthlyTrend: List<MonthRevenueResponse>,   // 최근 6개월 추이
    val bestSellers: List<BestSellerResponse>       // 베스트셀러 상품
)

@Serializable
data class SalesStatusBreakdownResponse(
    val pending: Int,
    val confirmed: Int,
    val shipping: Int,
    val delivered: Int,
    val cancelled: Int
)

@Serializable
data class MonthRevenueResponse(
    val month: String,      // "YYYY-MM"
    val revenue: String     // 해당 월 매출
)

@Serializable
data class BestSellerResponse(
    val productId: Int,
    val productName: String,
    val productImageUrl: String?,
    val quantity: Int,      // 판매 수량
    val revenue: String     // 매출액
)
