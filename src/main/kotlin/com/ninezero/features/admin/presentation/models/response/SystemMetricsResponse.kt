package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class SystemMetricsResponse(
    val uptime: Long,
    val cpuUsage: Double,
    val processMemory: Long,
    val memoryTotal: Long,
    val memoryAvailable: Long,
    val diskTotal: Long,
    val diskUsable: Long
)
