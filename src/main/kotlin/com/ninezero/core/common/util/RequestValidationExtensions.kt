package com.ninezero.core.common.util

import io.ktor.server.plugins.requestvalidation.ValidationResult

/** 검사 중 던진 예외 문구를 검증 실패로 */
inline fun validateRequest(block: () -> Unit): ValidationResult {
    return try {
        block()
        ValidationResult.Valid
    } catch (e: Exception) {
        ValidationResult.Invalid(e.message ?: "검증 실패")
    }
}
