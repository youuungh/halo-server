package com.ninezero.core.common.util

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.database.entities.user.UserDao
import com.auth0.jwt.interfaces.Payload
import com.ninezero.core.security.JwtConfig
import com.ninezero.plugins.UserSession
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.response.*
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

// JWT 인증 관련 확장 함수들

/** 미인증 시 401 후 null */
suspend fun ApplicationCall.requireUserId(): Int? {
    return getUserId() ?: run {
        respond(HttpStatusCode.Unauthorized, ApiResponse.error<Unit>(Errors.Common.AUTH_REQUIRED))
        null
    }
}

/** JWT 또는 세션 토큰에서 claim 추출 */
private fun <T> ApplicationCall.jwtClaim(extract: (Payload) -> T?): T? {
    // 세션 폴백 없음
    principal<JWTPrincipal>()?.let {
        return try {
            extract(it.payload)
        } catch (_: Exception) {
            null
        }
    }

    principal<UserSession>()?.let { session ->
        return try {
            extract(JwtConfig.verifier.verify(session.token))
        } catch (_: Exception) {
            null
        }
    }

    return null
}

fun ApplicationCall.getUserId(): Int? = jwtClaim { it.getClaim("userId").asInt() }

fun ApplicationCall.getUserRole(): String? = jwtClaim { it.getClaim("role").asString() }

fun ApplicationCall.getSessionIdOrNull(): String? = jwtClaim { it.getClaim("sessionId").asString() }

/** 미인증 401·비관리자 403 후 null */
suspend fun ApplicationCall.requireAdminId(): Int? {
    val role = getUserRole() ?: run {
        respond(HttpStatusCode.Unauthorized, ApiResponse.error<Unit>(Errors.Common.UNAUTHORIZED))
        return null
    }
    if (role != UserRole.ADMIN.name) {
        respond(HttpStatusCode.Forbidden, ApiResponse.error<Unit>(Errors.Common.ADMIN_ONLY))
        return null
    }
    val userId = getUserId() ?: run {
        respond(HttpStatusCode.Unauthorized, ApiResponse.error<Unit>(Errors.Common.UNAUTHORIZED))
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

fun ApplicationCall.getUserIdOrNull(): Int? {
    return try {
        val principal = this.principal<JWTPrincipal>() ?: return null
        principal.payload.getClaim("userId").asInt()
    } catch (_: Exception) {
        null
    }
}

// 파라미터 추출 관련 확장 함수들

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
    }
    return value
}

fun ApplicationCall.getOptionalIntParam(name: String, default: Int? = null): Int? {
    return parameters[name]?.toIntOrNull() ?: default
}

fun ApplicationCall.getIntParam(name: String, default: Int): Int {
    return parameters[name]?.toIntOrNull() ?: default
}

fun ApplicationCall.getOptionalStringParam(name: String, default: String? = null): String? {
    return parameters[name] ?: default
}

fun ApplicationCall.getStringParam(name: String, default: String): String {
    return parameters[name] ?: default
}

// 데이터베이스 트랜잭션 헬퍼 함수

suspend fun <T> query(block: suspend () -> T): T {
    // 중첩 금지
    // currentOrNull 직접 체크 금지
    return newSuspendedTransaction(Dispatchers.IO) { block() }
}

// 페이지네이션 정보 모델

data class PaginationInfo(
    val page: Int,
    val limit: Int,
    val totalCount: Int
) {
    val totalPages: Int = ceil(totalCount.toDouble() / limit).toInt()
    val hasNext: Boolean = page < totalPages
    val hasPrevious: Boolean = page > 1

    init {
        require(page > 0) { "page는 0보다 커야 합니다" }
        require(limit > 0) { "limit는 0보다 커야 합니다" }
        require(totalCount >= 0) { "totalCount는 음수일 수 없습니다" }
    }
}

@Serializable
data class PaginatedResponse<T>(
    val items: List<T>,
    val totalCount: Int,
    val page: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

fun <T> createPagedResponse(
    items: List<T>,
    pagination: PaginationInfo
): PaginatedResponse<T> {
    return PaginatedResponse(
        items = items,
        totalCount = pagination.totalCount,
        page = pagination.page,
        totalPages = pagination.totalPages,
        hasNext = pagination.hasNext,
        hasPrevious = pagination.hasPrevious
    )
}

data class CursorPaginationInfo(
    val limit: Int,
    val lastItemId: Int? = null,
    val hasNext: Boolean = false
) {
    init {
        require(limit > 0) { "limit는 0보다 커야 합니다" }
    }
}

// 페이지네이션 유효성 검사 함수들

fun validatePaginationParams(page: Int, limit: Int): Pair<Int, Int> {
    val validPage = maxOf(1, page)
    val validLimit = when {
        limit <= 0 -> Constants.DEFAULT_PAGE_LIMIT
        limit > Constants.MAX_PAGE_LIMIT -> Constants.MAX_PAGE_LIMIT
        else -> limit
    }
    return validPage to validLimit
}

fun validateCursorParams(limit: Int, lastItemId: Int?): Pair<Int, Int?> {
    val validLimit = when {
        limit <= 0 -> Constants.DEFAULT_PAGE_LIMIT
        limit > Constants.MAX_PAGE_LIMIT -> Constants.MAX_PAGE_LIMIT
        else -> limit
    }
    val validLastItemId = if (lastItemId != null && lastItemId > 0) lastItemId else null
    return validLimit to validLastItemId
}

// 확장 함수들

fun Int.toOffset(limit: Int): Long = ((this - 1) * limit).toLong()

/** 대소문자 무시 LIKE */
class ILikeOp(expr1: Expression<*>, expr2: Expression<*>) : ComparisonOp(expr1, expr2, "ILIKE")

infix fun <T : String?> Expression<T>.ilike(pattern: String): Op<Boolean> {
    return ILikeOp(this, stringParam(pattern))
}

// SubscriptionPlanTier 확장 함수들

/** 상위 티어는 하위 콘텐츠 접근 가능 */
fun SubscriptionPlanTier.canAccess(requiredTier: SubscriptionPlanTier): Boolean {
    return this.ordinal >= requiredTier.ordinal
}

fun SubscriptionPlanTier.toDisplayName(): String {
    return when (this) {
        SubscriptionPlanTier.FREE -> "무료"
        SubscriptionPlanTier.TIER1 -> "1티어"
        SubscriptionPlanTier.TIER2 -> "2티어"
    }
}

// RequestValidation 헬퍼

inline fun validateRequest(block: () -> Unit): ValidationResult {
    return try {
        block()
        ValidationResult.Valid
    } catch (e: Exception) {
        ValidationResult.Invalid(e.message ?: "검증 실패")
    }
}

// Logger 확장 함수

inline fun <reified T : Any> T.logger(): Logger {
    return LoggerFactory.getLogger(T::class.java)
}

fun logger(name: String): Logger {
    return LoggerFactory.getLogger(name)
}

// Micrometer MeterRegistry 확장 함수

fun MeterRegistry.gaugeValue(
    name: String,
    vararg tags: Pair<String, String>
): Double {
    // Search.tag는 requiredTags 매칭
    val search = tags.fold(this.find(name)) { acc, (key, value) -> acc.tag(key, value) }
    return search.gauge()?.value() ?: 0.0
}

// Cache 확장 함수들

suspend inline fun <reified T> CacheService.getJson(key: String): T? {
    return get(key) { json ->
        try {
            Json.decodeFromString<T>(json)
        } catch (_: Exception) {
            null
        }
    }
}

suspend inline fun <reified T> CacheService.setJson(
    key: String,
    value: T,
    ttl: Duration = 1.hours
) {
    set(key, value, { Json.encodeToString(it) }, ttl)
}
