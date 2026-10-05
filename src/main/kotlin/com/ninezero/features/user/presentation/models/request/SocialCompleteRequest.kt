package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class SocialCompleteRequest(
    val signupToken: String,
    val username: String,
    val fid: String? = null,
    val deviceId: String? = null
)
