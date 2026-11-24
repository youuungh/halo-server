package com.ninezero.features.monitoring.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class EndpointStatResponse(
    val endpoint: String,
    val count: Int,
    val avgTime: Double
)
