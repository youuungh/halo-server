package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class LogRequestResponse(
    val status: Int,
    val method: String,
    val path: String,
    val durationMs: Long
)
