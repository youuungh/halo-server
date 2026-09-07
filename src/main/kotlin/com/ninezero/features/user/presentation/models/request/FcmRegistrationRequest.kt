package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class FcmRegistrationRequest(
    val fid: String,
    val deviceId: String? = null
)