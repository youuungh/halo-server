package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class AuthResponse(
    val user: UserResponse,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)