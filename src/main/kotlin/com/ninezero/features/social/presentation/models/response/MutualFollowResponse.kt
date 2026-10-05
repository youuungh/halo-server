package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class MutualFollowResponse(
    val aFollowsB: Boolean,
    val bFollowsA: Boolean,
    val isMutualFollow: Boolean
)
