package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class BlockRelationResponse(
    val isBlockedByMe: Boolean,
    val isBlockedByOther: Boolean,
    val isBlocked: Boolean
)
