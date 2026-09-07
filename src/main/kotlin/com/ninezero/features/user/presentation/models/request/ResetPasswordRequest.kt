package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class ResetPasswordRequest(
    val token: String,
    val newPassword: String
)