package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class DeviceListResponse(
    val devices: List<DeviceResponse>,
    val totalCount: Int
)