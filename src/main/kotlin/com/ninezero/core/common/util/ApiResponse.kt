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
        
        fun error(error: String): ApiResponse<Unit> {
            return ApiResponse(success = false, error = error)
        }
        
        fun <T> error(error: String, data: T? = null): ApiResponse<T> {
            return ApiResponse(success = false, error = error, data = data)
        }
        
        fun success(message: String): ApiResponse<String> {
            return ApiResponse(success = true, message = message)
        }
    }
}
