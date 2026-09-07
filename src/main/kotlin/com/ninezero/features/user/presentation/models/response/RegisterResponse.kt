package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class RegisterResponse(
    val email: String,
    val username: String,
    val message: String,
    val needsVerification: Boolean = true
)