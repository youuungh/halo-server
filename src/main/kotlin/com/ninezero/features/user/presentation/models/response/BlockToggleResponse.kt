package com.ninezero.features.user.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class BlockToggleResponse(
    val isBlocked: Boolean,
    val message: String? = null
)
