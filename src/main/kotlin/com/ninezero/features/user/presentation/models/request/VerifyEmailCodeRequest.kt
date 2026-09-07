package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class VerifyEmailCodeRequest(
    val email: String,
    val code: String,
    val fid: String? = null,
    val deviceId: String? = null
)
