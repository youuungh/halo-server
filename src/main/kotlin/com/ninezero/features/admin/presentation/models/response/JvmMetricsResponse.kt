package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class JvmMetricsResponse(
    val heapUsed: Long,
    val heapMax: Long,
    val nonHeapUsed: Long,
    val nonHeapMax: Long,
    val threads: Int,
    val gcTime: Long
)