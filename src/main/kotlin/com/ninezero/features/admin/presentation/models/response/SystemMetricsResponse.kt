package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class SystemMetricsResponse(
    val uptime: Long,
    val cpuUsage: Double,
    val processMemory: Long
)