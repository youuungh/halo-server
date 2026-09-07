package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class VerifyResetCodeRequest(
    val email: String,
    val code: String
)
