package com.ninezero.features.search.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CreatorSearchResponse(
    val id: Int,
    val username: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val avatarThumbUrl: String? = null,
    val bio: String? = null,
    val role: String,
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val postCount: Int = 0,
    val productCount: Int = 0,
    val isFollowing: Boolean = false
)