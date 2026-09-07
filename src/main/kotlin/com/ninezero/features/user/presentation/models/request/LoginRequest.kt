package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val fid: String? = null,
    val deviceId: String? = null
)