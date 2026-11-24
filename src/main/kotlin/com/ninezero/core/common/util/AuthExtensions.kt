package com.ninezero.core.common.util

import com.auth0.jwt.interfaces.Payload
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.database.entities.user.UserDao
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

suspend fun ApplicationCall.requireUserId(): Int? {
    return getUserIdOrNull() ?: run {
        respond(HttpStatusCode.Unauthorized, ApiResponse.error<Unit>(Errors.Common.AUTH_REQUIRED))
        null
    }
}

private fun <T> ApplicationCall.jwtClaim(extract: (Payload) -> T?): T? {
    val principal = principal<JWTPrincipal>() ?: return null
    return try {
        extract(principal.payload)
    } catch (_: Exception) {
        null
    }
}

fun ApplicationCall.getUserIdOrNull(): Int? = jwtClaim { it.getClaim("userId").asInt() }

private fun ApplicationCall.getUserRole(): String? = jwtClaim { it.getClaim("role").asString() }

fun ApplicationCall.getSessionIdOrNull(): String? = jwtClaim { it.getClaim("sessionId").asString() }

suspend fun ApplicationCall.requireAdminId(): Int? {
    val role = getUserRole() ?: run {
        respond(HttpStatusCode.Unauthorized, ApiResponse.error<Unit>(Errors.Common.AUTH_REQUIRED))
        return null
    }
    if (role != UserRole.ADMIN.name) {
        respond(HttpStatusCode.Forbidden, ApiResponse.error<Unit>(Errors.Common.ADMIN_ONLY))
        return null
    }
    val userId = getUserIdOrNull() ?: run {
        respond(HttpStatusCode.Unauthorized, ApiResponse.error<Unit>(Errors.Common.AUTH_REQUIRED))
        return null
    }
    // role은 토큰 스냅샷, DB로 재확인
    val currentRole = query { UserDao.findById(userId)?.role }
    if (currentRole != UserRole.ADMIN) {
        respond(HttpStatusCode.Forbidden, ApiResponse.error<Unit>(Errors.Common.ADMIN_ONLY))
        return null
    }
    return userId
}
