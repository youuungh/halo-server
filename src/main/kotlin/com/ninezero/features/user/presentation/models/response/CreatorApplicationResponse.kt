package com.ninezero.features.user.presentation.models.response

import com.ninezero.core.common.config.CreatorApplicationStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class CreatorApplicationResponse(
    val id: Int,
    val userId: Int,
    val username: String,
    val reason: String,
    val portfolioUrl: String?,
    val status: CreatorApplicationStatus,
    val rejectionReason: String?,
    val reviewedBy: Int?,
    val createdAt: LocalDateTime,
    val reviewedAt: LocalDateTime?
)
