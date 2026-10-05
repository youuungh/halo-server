package com.ninezero.features.tag.presentation.models.request

import com.ninezero.core.common.config.TagTargetType
import kotlinx.serialization.Serializable

@Serializable
data class TagRequest(
    val name: String,
    val targetType: TagTargetType,
    val isSectionEnabled: Boolean = false
)
