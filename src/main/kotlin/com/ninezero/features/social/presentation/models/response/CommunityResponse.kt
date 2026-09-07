package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CommunityResponse(
    val pinnedPosts: List<PostResponse>,
    val posts: PostListResponse
)
