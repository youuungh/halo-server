package com.ninezero.features.tag.presentation.models.response

import com.ninezero.core.common.config.TagTargetType
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class TagResponse(
    val id: Int,
    val name: String,
    val targetType: TagTargetType,
    val isSectionEnabled: Boolean,
    val postCount: Int? = null,
    val productCount: Int? = null,
    val createdAt: LocalDateTime
)
