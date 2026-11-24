package com.ninezero.core.common.util

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

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
