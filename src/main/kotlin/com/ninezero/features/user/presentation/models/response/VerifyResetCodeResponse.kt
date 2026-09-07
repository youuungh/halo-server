package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class VerifyResetCodeResponse(
    val resetToken: String
)
