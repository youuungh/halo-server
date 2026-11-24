package com.ninezero.features.monitoring.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class LogSourceResponse(
    val name: String,
    val count: Int
)
