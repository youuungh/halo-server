package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class FollowedCreatorResponse(
    val id: Int,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val avatarThumbUrl: String? = null,
    val notifyNewPost: Boolean = false,
    val notifyNewProduct: Boolean = false
)
