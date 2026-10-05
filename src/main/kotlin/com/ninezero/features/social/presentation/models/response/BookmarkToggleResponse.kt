package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class BookmarkToggleResponse(
    val isBookmarked: Boolean,
    val message: String? = null
)
