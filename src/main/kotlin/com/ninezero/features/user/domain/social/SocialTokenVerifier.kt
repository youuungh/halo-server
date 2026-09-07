package com.ninezero.features.user.domain.social

import com.ninezero.core.common.config.SocialProvider

/** 검증된 소셜 프로필 */
data class SocialIdentity(
    val provider: SocialProvider,
    val providerUserId: String,
    val email: String?,
    val emailVerified: Boolean,
    val name: String?,
    val picture: String?
)

/** 간편로그인 토큰 검증기 */
interface SocialTokenVerifier {
    val provider: SocialProvider

    suspend fun verify(token: String): SocialIdentity
}
