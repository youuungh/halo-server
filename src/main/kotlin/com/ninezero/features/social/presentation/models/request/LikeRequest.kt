package com.ninezero.features.social.presentation.models.request

import com.ninezero.core.common.config.LikeType
import kotlinx.serialization.Serializable

@Serializable
data class LikeRequest(
    val targetId: Int,
    val targetType: LikeType // POST 또는 COMMENT
)
