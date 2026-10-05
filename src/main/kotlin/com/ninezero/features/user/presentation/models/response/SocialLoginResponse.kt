package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class SocialLoginResponse(
    val needsUsername: Boolean,  // false=로그인 완료, true=온보딩 필요
    val auth: AuthResponse? = null,
    val signupToken: String? = null,
    val suggestedUsername: String? = null
)
