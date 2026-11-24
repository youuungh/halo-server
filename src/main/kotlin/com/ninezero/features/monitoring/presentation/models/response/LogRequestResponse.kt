package com.ninezero.features.monitoring.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class LogRequestResponse(
    val status: Int,
    val method: String,
    val path: String,
    val durationMs: Long
)
