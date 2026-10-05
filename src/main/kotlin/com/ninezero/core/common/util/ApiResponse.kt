package com.ninezero.core.common.util

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
    val error: String? = null
) {
    companion object {
        fun <T> success(data: T, message: String? = null): ApiResponse<T> {
            return ApiResponse(success = true, data = data, message = message)
        }

        fun <T> error(error: String): ApiResponse<T> {
            return ApiResponse(success = false, error = error)
        }

        fun success(message: String): ApiResponse<String> {
            return ApiResponse(success = true, message = message)
        }
    }
}
