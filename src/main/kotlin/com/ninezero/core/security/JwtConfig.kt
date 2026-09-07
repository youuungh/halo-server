package com.ninezero.core.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.DotenvConfig
import java.util.*
import kotlin.time.Duration.Companion.minutes

object JwtConfig {
    private val secret = DotenvConfig.getRequired("JWT_SECRET")

    const val ISSUER = "hub-api"
    const val AUDIENCE = "hub-users"
    const val REALM = "hub"

    // 소셜 가입 전용 audience
    private const val SOCIAL_SIGNUP_AUDIENCE = "hub-social-signup"

    val ACCESS_VALIDITY_MS = Constants.User.ACCESS_TOKEN_VALIDITY_MINUTES.minutes.inWholeMilliseconds
    val SOCIAL_SIGNUP_VALIDITY_MS = Constants.User.SOCIAL_SIGNUP_TOKEN_VALIDITY_MINUTES.minutes.inWholeMilliseconds

    val verifier: JWTVerifier = JWT
        .require(Algorithm.HMAC256(secret))
        .withAudience(AUDIENCE)
        .withIssuer(ISSUER)
        .build()

    val socialSignupVerifier: JWTVerifier = JWT
        .require(Algorithm.HMAC256(secret))
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
        .sign(Algorithm.HMAC256(secret))

    fun makeRefreshToken(): String = UUID.randomUUID().toString()  // 발급 후 DB에 저장됨

    fun makeSocialSignupToken(
        provider: String,
        providerUserId: String,
        email: String,
        name: String?,
        picture: String?,
        validityMs: Long = SOCIAL_SIGNUP_VALIDITY_MS  // 테스트용 만료 파라미터
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
        return builder.sign(Algorithm.HMAC256(secret))
    }
}
