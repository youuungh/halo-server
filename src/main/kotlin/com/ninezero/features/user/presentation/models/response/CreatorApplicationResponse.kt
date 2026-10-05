package com.ninezero.features.user.presentation.models.response

import com.ninezero.core.common.config.CreatorApplicationStatus
import com.ninezero.core.common.util.PaginatedResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class CreatorApplicationResponse(
    val id: Int,
    val userId: Int,
    val username: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val reason: String,
    val portfolioUrl: String? = null,
    val status: CreatorApplicationStatus,
    val rejectionReason: String? = null,
    val reviewedBy: Int? = null,
    val createdAt: LocalDateTime,
    val reviewedAt: LocalDateTime? = null
)

typealias CreatorApplicationListResponse = PaginatedResponse<CreatorApplicationResponse>
