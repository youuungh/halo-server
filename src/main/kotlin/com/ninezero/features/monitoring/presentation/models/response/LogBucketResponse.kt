package com.ninezero.features.monitoring.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class LogBucketResponse(
    val start: LocalDateTime,
    val error: Int,
    val warn: Int,
    val info: Int
)
