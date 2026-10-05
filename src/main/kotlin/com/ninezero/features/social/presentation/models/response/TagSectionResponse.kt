package com.ninezero.features.social.presentation.models.response

import com.ninezero.features.tag.presentation.models.response.TagResponse
import kotlinx.serialization.Serializable

@Serializable
data class TagSectionResponse(
    val tag: TagResponse,
    val posts: List<PostResponse>,
    val hasMore: Boolean
)
