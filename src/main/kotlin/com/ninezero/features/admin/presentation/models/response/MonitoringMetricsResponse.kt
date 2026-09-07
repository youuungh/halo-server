package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class MonitoringMetricsResponse(
    val http: HttpMetricsResponse,
    val jvm: JvmMetricsResponse,
    val database: DatabaseMetricsResponse,
    val system: SystemMetricsResponse
)