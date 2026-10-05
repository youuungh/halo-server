package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class PinToggleResponse(
    val isPinned: Boolean
)
