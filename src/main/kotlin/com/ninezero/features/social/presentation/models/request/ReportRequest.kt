package com.ninezero.features.social.presentation.models.request

import com.ninezero.core.common.config.ReportTargetType
import kotlinx.serialization.Serializable

@Serializable
data class ReportRequest(
    val targetType: ReportTargetType,
    val targetId: Int
)
