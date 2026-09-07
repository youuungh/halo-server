package com.ninezero.features.user.domain.social

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.config.SocialProvider
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidCredentialsException
import com.ninezero.core.common.util.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** 구글 idToken 검증 */
class GoogleTokenVerifier : SocialTokenVerifier {
    override val provider = SocialProvider.GOOGLE

    private val logger = logger()

    // 미사용 환경 대비 lazy
    private val clientId by lazy { DotenvConfig.getRequired("GOOGLE_CLIENT_ID") }

    private val jwkProvider by lazy {
        JwkProviderBuilder(URI(GOOGLE_JWKS_URL).toURL())
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()
    }

    override suspend fun verify(token: String): SocialIdentity = withContext(Dispatchers.IO) {
        val verified = try {
            val keyId = JWT.decode(token).keyId
            val publicKey = jwkProvider.get(keyId).publicKey as RSAPublicKey
            JWT.require(Algorithm.RSA256(publicKey, null))
                .withAudience(clientId)  // 앱 serverClientId와 동일
                .withIssuer(*GOOGLE_ISSUERS)
                // 서버-구글 시계 오차 허용
                .acceptLeeway(CLOCK_SKEW_LEEWAY_SECONDS)
                .build()
                .verify(token)
        } catch (e: JWTVerificationException) {
            logger.warn("구글 idToken 검증 실패: ${e.message}")
            throw InvalidCredentialsException(Errors.User.INVALID_SOCIAL_TOKEN)
        } catch (e: JwkException) {
            logger.warn("구글 JWKS 키 조회 실패: ${e.message}")
            throw InvalidCredentialsException(Errors.User.INVALID_SOCIAL_TOKEN)
        }

        val sub = verified.subject
            ?: throw InvalidCredentialsException(Errors.User.INVALID_SOCIAL_TOKEN)

        SocialIdentity(
            provider = SocialProvider.GOOGLE,
            providerUserId = sub,
            email = verified.getClaim("email").asString(),
            // boolean 아닌 문자열 응답 방어
            emailVerified = verified.getClaim("email_verified").let {
                it.asBoolean() ?: it.asString()?.toBoolean()
            } ?: false,
            name = verified.getClaim("name").asString(),
            picture = verified.getClaim("picture").asString()
        )
    }

    companion object {
        private const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
        private val GOOGLE_ISSUERS = arrayOf("https://accounts.google.com", "accounts.google.com")
        private const val CLOCK_SKEW_LEEWAY_SECONDS = 60L
    }
}
