package com.ninezero.features.tag.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class TagListResponse(
    val tags: List<TagResponse>
)
