package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CreatorStatisticsResponse(
    val totalApplications: Int,
    val pendingApplications: Int,
    val approvedApplications: Int,
    val rejectedApplications: Int,
    val revokedApplications: Int,
    val applicationsToday: Int,
    val applicationsThisWeek: Int,
    val applicationsThisMonth: Int,
    val totalActiveCreators: Int,
    val avgApprovalTime: String
)