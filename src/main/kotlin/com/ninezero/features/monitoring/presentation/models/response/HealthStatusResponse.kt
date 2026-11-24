package com.ninezero.features.monitoring.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class HealthStatusResponse(
    val status: String,
    val uptime: Long
)
