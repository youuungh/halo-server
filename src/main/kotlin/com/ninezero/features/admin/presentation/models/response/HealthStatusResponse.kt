package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class HealthStatusResponse(
    val status: String,
    val uptime: Long,
    val version: String
)