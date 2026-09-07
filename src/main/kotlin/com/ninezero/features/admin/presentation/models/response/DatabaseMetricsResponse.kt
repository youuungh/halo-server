package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class DatabaseMetricsResponse(
    val activeConnections: Int,
    val idleConnections: Int,
    val totalConnections: Int,
    val maxConnections: Int
)