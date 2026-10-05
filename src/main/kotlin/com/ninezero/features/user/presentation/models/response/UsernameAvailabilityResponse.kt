package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class UsernameAvailabilityResponse(
    val available: Boolean
)
