package com.ninezero.features.user.presentation.models.response

import com.ninezero.core.common.config.UserRole
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class UserResponse(
    val id: Int,
    val email: String,
    val username: String,
    val role: UserRole,
    val createdAt: LocalDateTime,
    val hasPassword: Boolean = true,  // 비번 변경 메뉴 분기용
    val socialProviders: List<String> = emptyList()
)
