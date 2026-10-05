package com.ninezero.features.social.presentation.models.response

import com.ninezero.core.common.config.ReportTargetType
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class ReportedTargetDetailResponse(
    val targetType: ReportTargetType,
    val targetId: Int,
    val reportCount: Int,
    val isBlinded: Boolean,  // 이 응답만 블라인드 대상도 포함
    val moderationLocked: Boolean,
    val author: UserSummaryResponse? = null,
    val content: String? = null,
    val mediaItems: List<MediaItemResponse> = emptyList(),
    val likeCount: Int? = null,
    val commentCount: Int? = null,
    val createdAt: LocalDateTime? = null
)
