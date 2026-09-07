package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CreatorProfileResponse(
    val pinnedPosts: List<PostResponse>,
    val tagSections: List<TagSectionResponse>,
    val allPosts: PostListResponse
)
