package com.ninezero.features.social.presentation.models.response

import com.ninezero.core.common.config.ReportTargetType
import kotlinx.serialization.Serializable

@Serializable
data class ReportedTargetResponse(
    val targetType: ReportTargetType,
    val targetId: Int,
    val reportCount: Int,
    val isBlinded: Boolean,
    val moderationLocked: Boolean
)
