package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class UserStatisticsResponse(
    val totalUsers: Int,
    val totalCreators: Int,
    val totalAdmins: Int,
    val newUsersToday: Int,
    val newUsersThisWeek: Int,
    val newUsersThisMonth: Int,
    val activeUsers: Int
)