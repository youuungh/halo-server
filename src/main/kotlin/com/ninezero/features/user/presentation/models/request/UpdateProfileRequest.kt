package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val bio: String? = null,
    val location: String? = null,
    val website: String? = null
)