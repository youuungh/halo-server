package com.ninezero.features.user.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class DeviceResponse(
    val id: String,
    val deviceType: String,
    val deviceName: String,
    val browser: String,
    val os: String,
    val ipAddress: String,
    val location: String?,
    val lastActive: LocalDateTime,
    val isCurrent: Boolean,
    val createdAt: LocalDateTime
)
