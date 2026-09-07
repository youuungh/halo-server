package com.ninezero.plugins

import com.ninezero.core.common.config.DotenvConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

fun Application.configureHTTP() {
    val environment = DotenvConfig.getOrDefault("ENVIRONMENT", "dev")
    val isDev = environment != "prod"

    // 리버스 프록시 뒤 remoteHost 복원용
    install(XForwardedHeaders)

    install(RateLimit) {
        val defaultLimit = if (isDev) 1000 else 100
        val paymentLimit = if (isDev) 1000 else 10
        val creatorApplicationLimit = if (isDev) 100 else 1
        val authLimit = if (isDev) 1000 else 5
        val authRefreshLimit = if (isDev) 1000 else 30
        val creatorLimit = if (isDev) 100 else 1

        register {
            rateLimiter(limit = defaultLimit, refillPeriod = 60.seconds)
        }
        register(RateLimitName("payment")) {
            rateLimiter(limit = paymentLimit, refillPeriod = 60.seconds)
            requestKey { call ->
                call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
                    ?: call.request.origin.remoteHost
            }
        }
        register(RateLimitName("creator-application")) {
            rateLimiter(limit = creatorApplicationLimit, refillPeriod = 1.hours)
        }
        register(RateLimitName("auth")) {
            rateLimiter(limit = authLimit, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName("auth-refresh")) {
            rateLimiter(limit = authRefreshLimit, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName("creator")) {
            rateLimiter(limit = creatorLimit, refillPeriod = 1.hours)
            requestKey { call ->
                call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
                    ?: call.request.origin.remoteHost
            }
        }
    }
    install(CORS) {
        val allowedHosts = DotenvConfig["ALLOWED_HOSTS"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: listOf("localhost:3000", "localhost:3001")  // 개발 기본값

        allowedHosts.forEach { host ->
            allowHost(host, schemes = listOf("http", "https"))
        }

        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)

        allowCredentials = true

        maxAgeInSeconds = 86400
    }
    install(DefaultHeaders)
    install(Compression)

    install(RequestBodyLimit) {
        bodyLimit { 320L * 1024 * 1024 }  // 300MB
    }
}
