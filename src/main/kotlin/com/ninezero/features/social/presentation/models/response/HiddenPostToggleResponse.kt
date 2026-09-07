package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class HiddenPostToggleResponse(
    val isHidden: Boolean,
    val message: String? = null
)
