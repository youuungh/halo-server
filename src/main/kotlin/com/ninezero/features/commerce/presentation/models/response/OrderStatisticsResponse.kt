package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class OrderStatisticsResponse(
    val totalOrders: Int,
    val totalRevenue: String,
    val ordersToday: Int,
    val ordersThisWeek: Int,
    val ordersThisMonth: Int,
    val revenueToday: String,
    val revenueThisWeek: String,
    val revenueThisMonth: String,
    val avgOrderValue: String,
    val pendingOrders: Int,
    val completedOrders: Int,
    val cancelledOrders: Int
)
