package com.ninezero.features.monitoring.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class LogLevelCountsResponse(
    val error: Int,
    val warn: Int,
    val info: Int
)
