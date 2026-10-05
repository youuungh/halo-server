package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class SocialLoginRequest(
    val provider: String,
    /** 제공자별 자격 토큰 */
    val token: String,
    val fid: String? = null,
    val deviceId: String? = null
)
