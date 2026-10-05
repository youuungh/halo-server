package com.ninezero.features.social.presentation.models.response

import com.ninezero.core.common.util.CursorResponse
import kotlinx.serialization.Serializable

@Serializable
data class CommunityResponse(
    val pinnedPosts: List<PostResponse>,
    val posts: CursorResponse<PostResponse>
)
