package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class HttpMetricsResponse(
    val avgResponseTime: Double,
    val errorRate: Double,  // 4xx+5xx
    val serverErrorRate: Double,  // 5xx만
    val totalRequests: Int,
    val endpointStats: List<EndpointStatResponse>
)
