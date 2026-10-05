package com.ninezero.core.common.util

import com.auth0.jwt.interfaces.Payload
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.database.entities.user.UserDao
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// JWT 인증

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

// 파라미터 추출

suspend fun ApplicationCall.getRequiredIntParam(
    name: String,
    errorMessage: String = Errors.Common.INVALID_ID
): Int? {
    val value = parameters[name]?.toIntOrNull()
    if (value == null) {
        respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>(errorMessage))  // 없거나 잘못되면 자동 400 후 null
    }
    return value
}

suspend fun ApplicationCall.getRequiredStringParam(
    name: String,
    errorMessage: String = Errors.Common.INVALID_REQUEST
): String? {
    val value = parameters[name]
    if (value.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>(errorMessage))  // 없거나 비어있으면 자동 400 후 null
        return null
    }
    return value
}

fun ApplicationCall.getOptionalIntParam(name: String): Int? {
    return parameters[name]?.toIntOrNull()
}

fun ApplicationCall.getIntParam(name: String, default: Int): Int {
    return parameters[name]?.toIntOrNull() ?: default
}

fun ApplicationCall.getOptionalStringParam(name: String): String? {
    return parameters[name]
}

/** true·false 외 값은 400 */
fun ApplicationCall.getOptionalBooleanParam(name: String): Boolean? {
    val value = parameters[name] ?: return null
    return value.toBooleanStrictOrNull() ?: throw InvalidInputException(Errors.Common.INVALID_BOOLEAN_FORMAT)
}

suspend fun ApplicationCall.getRequiredBooleanParam(name: String, errorMessage: String): Boolean? {
    val value = parameters[name]?.toBooleanStrictOrNull()
    if (value == null) {
        respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>(errorMessage))  // 없거나 잘못되면 자동 400 후 null
    }
    return value
}

inline fun <reified T : Enum<T>> ApplicationCall.getOptionalEnumParam(name: String): T? {
    val value = parameters[name] ?: return null
    return runCatching { enumValueOf<T>(value.uppercase()) }.getOrNull()  // 모르는 값은 필터 없음
}

// 트랜잭션

suspend fun <T> query(block: suspend () -> T): T {
    return newSuspendedTransaction(Dispatchers.IO) { block() }  // 중첩·currentOrNull 직접 체크 금지
}

// ILIKE

/** 대소문자 무시 LIKE */
private class ILikeOp(expr1: Expression<*>, expr2: Expression<*>, private val escapeChar: Char?) :
    ComparisonOp(expr1, expr2, "ILIKE") {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        super.toQueryBuilder(queryBuilder)
        if (escapeChar != null) {
            with(queryBuilder) {
                +" ESCAPE "
                +stringParam(escapeChar.toString())
            }
        }
    }
}

infix fun <T : String?> Expression<T>.ilike(pattern: LikePattern): Op<Boolean> {
    return ILikeOp(this, stringParam(pattern.pattern), pattern.escapeChar)
}

/** 검색어를 글자 그대로 포함하는 LIKE 패턴 */
fun containsLikePattern(text: String): LikePattern =
    LikePattern.ofLiteral(text).let { LikePattern("%${it.pattern}%", it.escapeChar) }  // %·_ 이스케이프

// 구독 티어

/** 상위 티어는 하위 콘텐츠 접근 가능 */
fun SubscriptionPlanTier.canAccess(requiredTier: SubscriptionPlanTier): Boolean {
    return this.ordinal >= requiredTier.ordinal
}

// 로거

inline fun <reified T : Any> T.logger(): Logger {
    return LoggerFactory.getLogger(T::class.java)
}

fun logger(name: String): Logger {
    return LoggerFactory.getLogger(name)
}
