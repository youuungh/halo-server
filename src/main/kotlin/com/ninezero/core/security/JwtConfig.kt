package com.ninezero.core.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.DotenvConfig
import java.util.*
import kotlin.time.Duration.Companion.minutes

object JwtConfig {
    private val algorithm = Algorithm.HMAC256(DotenvConfig.getRequired("JWT_SECRET"))

    private const val ISSUER = "hub-api"
    private const val AUDIENCE = "hub-users"
    const val REALM = "hub"

    // 소셜 가입 전용 audience
    private const val SOCIAL_SIGNUP_AUDIENCE = "hub-social-signup"

    private val ACCESS_VALIDITY_MS = Constants.User.ACCESS_TOKEN_VALIDITY_MINUTES.minutes.inWholeMilliseconds
    private val SOCIAL_SIGNUP_VALIDITY_MS = Constants.User.SOCIAL_SIGNUP_TOKEN_VALIDITY_MINUTES.minutes.inWholeMilliseconds

    val verifier: JWTVerifier = JWT
        .require(algorithm)
        .withAudience(AUDIENCE)
        .withIssuer(ISSUER)
        .build()

    val socialSignupVerifier: JWTVerifier = JWT
        .require(algorithm)
        .withAudience(SOCIAL_SIGNUP_AUDIENCE)
        .withIssuer(ISSUER)
        .build()

    fun makeAccessToken(userId: Int, email: String, username: String, role: String, sessionId: String): String = JWT.create()
        .withSubject("Authentication")
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withClaim("userId", userId)
        .withClaim("email", email)
        .withClaim("username", username)
        .withClaim("role", role)
        .withClaim("sessionId", sessionId)
        .withExpiresAt(Date(System.currentTimeMillis() + ACCESS_VALIDITY_MS))
        .sign(algorithm)

    fun makeRefreshToken(): String = UUID.randomUUID().toString()  // 발급 후 DB에 저장됨

    fun makeSocialSignupToken(
        provider: String,
        providerUserId: String,
        email: String,
        name: String?,
        picture: String?
    ): String = buildSocialSignupToken(provider, providerUserId, email, name, picture, SOCIAL_SIGNUP_VALIDITY_MS)

    /** 만료 거부 검증용 */
    internal fun makeExpiredSocialSignupToken(provider: String, providerUserId: String, email: String): String =
        buildSocialSignupToken(provider, providerUserId, email, null, null, -1_000L)

    private fun buildSocialSignupToken(
        provider: String,
        providerUserId: String,
        email: String,
        name: String?,
        picture: String?,
        validityMs: Long
    ): String {
        val builder = JWT.create()  // username 확정 전까지 DB 저장 없음
            .withSubject("SocialSignup")
            .withIssuer(ISSUER)
            .withAudience(SOCIAL_SIGNUP_AUDIENCE)
            .withClaim("provider", provider)
            .withClaim("providerUserId", providerUserId)
            .withClaim("email", email)
            .withExpiresAt(Date(System.currentTimeMillis() + validityMs))
        name?.let { builder.withClaim("name", it) }
        picture?.let { builder.withClaim("picture", it) }
        return builder.sign(algorithm)
    }
}
