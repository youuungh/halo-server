package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.util.BUSINESS_TIMEZONE
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.startOfBusinessDayUtc
import com.ninezero.core.common.util.statisticsDateBoundaries
import com.ninezero.core.common.util.toAmountString
import com.ninezero.features.commerce.data.OrderRepository
import com.ninezero.features.commerce.presentation.models.response.BestSellerResponse
import com.ninezero.features.commerce.presentation.models.response.CreatorMonthlySalesResponse
import com.ninezero.features.commerce.presentation.models.response.CreatorSalesSummaryResponse
import com.ninezero.features.commerce.presentation.models.response.MonthRevenueResponse
import com.ninezero.features.commerce.presentation.models.response.OrderStatisticsResponse
import com.ninezero.features.commerce.presentation.models.response.SalesStatusBreakdownResponse
import com.ninezero.features.user.data.UserRepository
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import kotlin.time.Clock

/** 관리자 주문 지표와 크리에이터 매출 집계 */
class CreatorSalesService(
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository
) {

    private companion object {
        const val BEST_SELLER_LIMIT = 5
        const val TREND_MONTHS = 6
    }

    suspend fun getOrderStatistics(): OrderStatisticsResponse {
        val (todayStart, tomorrowStart, weekStart, monthStart) = statisticsDateBoundaries()

        return query {
            OrderStatisticsResponse(
                totalOrders = orderRepository.countAllOrders(),
                totalRevenue = orderRepository.sumTotalRevenue().toAmountString(),
                ordersToday = orderRepository.countOrdersByDateRange(todayStart, tomorrowStart),
                ordersThisWeek = orderRepository.countOrdersByDateRange(weekStart, tomorrowStart),
                ordersThisMonth = orderRepository.countOrdersByDateRange(monthStart, tomorrowStart),
                revenueToday = orderRepository.sumRevenueByDateRange(todayStart, tomorrowStart).toAmountString(),
                revenueThisWeek = orderRepository.sumRevenueByDateRange(weekStart, tomorrowStart).toAmountString(),
                revenueThisMonth = orderRepository.sumRevenueByDateRange(monthStart, tomorrowStart).toAmountString(),
                avgOrderValue = orderRepository.calcAvgOrderValue().toAmountString(),
                pendingOrders = orderRepository.countOrdersByStatus(OrderStatus.PENDING),
                completedOrders = orderRepository.countOrdersByStatus(OrderStatus.DELIVERED),
                cancelledOrders = orderRepository.countOrdersByStatus(OrderStatus.CANCELLED)
            )
        }
    }

    suspend fun getCreatorSalesSummary(creatorId: Int): CreatorSalesSummaryResponse {
        return query {
            requireCreatorRole(userRepository, creatorId)

            val currentMonth = currentBusinessYearMonth()
            val (monthStart, monthEnd) = monthRange(currentMonth)
            val (lastMonthStart, lastMonthEnd) = monthRange(currentMonth.minusMonths(1))

            val allTime = orderRepository.aggregateCreatorSales(creatorId, null, null)
            val thisMonth = orderRepository.aggregateCreatorSales(creatorId, monthStart, monthEnd)
            val lastMonthGross = orderRepository.sumCreatorGrossRevenue(creatorId, lastMonthStart, lastMonthEnd)

            CreatorSalesSummaryResponse(
                thisMonthRevenue = thisMonth.grossRevenue.toAmountString(),
                thisMonthNetRevenue = (thisMonth.grossRevenue - thisMonth.refundAmount).toAmountString(),
                lastMonthRevenue = lastMonthGross.toAmountString(),
                cumulativeRevenue = allTime.grossRevenue.toAmountString(),
                pendingCount = allTime.pendingCount,
                confirmedCount = allTime.confirmedCount,
                shippingCount = allTime.shippingCount,
                deliveredCount = allTime.deliveredCount,
                cancelledCount = allTime.cancelledCount
            )
        }
    }

    suspend fun getCreatorMonthlySales(creatorId: Int, month: String?): CreatorMonthlySalesResponse {
        val yearMonth = parseYearMonth(month)
        val (start, end) = monthRange(yearMonth)

        return query {
            requireCreatorRole(userRepository, creatorId)

            val agg = orderRepository.aggregateCreatorSales(creatorId, start, end)
            val top = orderRepository.topCreatorProducts(creatorId, start, end, BEST_SELLER_LIMIT)

            // 최근 6개월 추이
            val trendMonths = (TREND_MONTHS - 1 downTo 0).map { offset -> yearMonth.minusMonths(offset.toLong()) }
            val trendRevenues = orderRepository.sumCreatorMonthlyGrossRevenue(creatorId, trendMonths.map { monthRange(it) })
            val trend = trendMonths.zip(trendRevenues) { m, revenue ->
                MonthRevenueResponse(
                    month = m.toString(),
                    revenue = revenue.toAmountString()
                )
            }

            val net = agg.grossRevenue - agg.refundAmount
            val avgOrderValue = if (agg.orderCount > 0) {
                agg.grossRevenue.divide(BigDecimal(agg.orderCount), 0, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }

            CreatorMonthlySalesResponse(
                month = yearMonth.toString(),
                grossRevenue = agg.grossRevenue.toAmountString(),
                refundAmount = agg.refundAmount.toAmountString(),
                netRevenue = net.toAmountString(),
                orderCount = agg.orderCount,
                quantitySold = agg.quantitySold,
                avgOrderValue = avgOrderValue.toAmountString(),
                shippingFees = agg.shippingFees.toAmountString(),
                couponDiscounts = agg.couponDiscounts.toAmountString(),
                statusBreakdown = SalesStatusBreakdownResponse(
                    pending = agg.pendingCount,
                    confirmed = agg.confirmedCount,
                    shipping = agg.shippingCount,
                    delivered = agg.deliveredCount,
                    cancelled = agg.cancelledCount
                ),
                monthlyTrend = trend,
                bestSellers = top.map {
                    BestSellerResponse(
                        productId = it.productId,
                        productName = it.productName,
                        productImageUrl = it.productImageUrl,
                        quantity = it.quantity,
                        revenue = it.revenue.toAmountString()
                    )
                }
            )
        }
    }

    /** yyyy-MM이 아니면 이번 달 */
    private fun parseYearMonth(month: String?): YearMonth =
        month?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: currentBusinessYearMonth()

    /** 업무 시간대 기준 이번 달 */
    private fun currentBusinessYearMonth(): YearMonth {
        val today = Clock.System.now().toLocalDateTime(BUSINESS_TIMEZONE).date
        return YearMonth.of(today.year, today.month.number)
    }

    /** 업무 시간대 한 달의 UTC 범위 */
    private fun monthRange(yearMonth: YearMonth): Pair<LocalDateTime, LocalDateTime> {
        val start = LocalDate(yearMonth.year, yearMonth.monthValue, 1)
        val nextStart = start.plus(DatePeriod(months = 1))
        return start.startOfBusinessDayUtc() to nextStart.startOfBusinessDayUtc()
    }
}
