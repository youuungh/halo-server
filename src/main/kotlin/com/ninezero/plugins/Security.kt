package com.ninezero.plugins

import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.security.JwtConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import com.ninezero.core.common.config.DotenvConfig
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Security")

@Serializable
data class UserSession(val token: String)

fun Application.configureSecurity() {
    val environment = DotenvConfig.getOrDefault("ENVIRONMENT", "dev")
    val isProduction = environment == "prod"

    install(Sessions) {
        cookie<UserSession>("user_session") {
            cookie.path = "/"
            if (isProduction) {
                cookie.domain = DotenvConfig.getOrDefault("COOKIE_DOMAIN", "")
            }
            cookie.httpOnly = true
            cookie.secure = isProduction
            cookie.extensions["SameSite"] = if (isProduction) "Lax" else "None"
            cookie.maxAgeInSeconds = 60 * 60 * 24 * 7  // 7일
        }
    }

    authentication {
        // JWT 인증
        jwt("jwt") {
            realm = JwtConfig.REALM
            verifier(JwtConfig.verifier)
            validate { credential ->
                if (credential.payload.audience.contains(JwtConfig.AUDIENCE)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }

        // 세션 인증
        session<UserSession>("session-auth") {
            validate { session ->
                logger.debug("세션 검증 시작")
                try {
                    val verifier = JwtConfig.verifier
                    val decodedJWT = verifier.verify(session.token)
                    if (decodedJWT.audience.contains(JwtConfig.AUDIENCE)) {
                        logger.debug("세션 검증 성공!")
                        session
                    } else {
                        logger.warn("세션 검증 실패: audience 불일치")
                        null
                    }
                } catch (e: Exception) {
                    logger.error("세션 검증 실패: ${e.message}")
                    null
                }
            }
            challenge {
                logger.debug("세션 인증 challenge 호출")
                call.respond(HttpStatusCode.Unauthorized, ApiResponse.error<Unit>("Session required"))
            }
        }
    }
}
