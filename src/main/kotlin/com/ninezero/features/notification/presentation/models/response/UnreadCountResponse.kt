package com.ninezero.features.notification.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class UnreadCountResponse(
    val count: Int
)