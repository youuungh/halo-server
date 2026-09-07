package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class FollowResponse(
    val followerCount: Int,
    val followingCount: Int,
    val userId: Int? = null
)
