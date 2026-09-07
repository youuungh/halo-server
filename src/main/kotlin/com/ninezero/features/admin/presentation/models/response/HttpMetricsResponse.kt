package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class HttpMetricsResponse(
    val avgResponseTime: Double,
    val errorRate: Double,
    val totalRequests: Int,
    val endpointStats: List<EndpointStatResponse>
)