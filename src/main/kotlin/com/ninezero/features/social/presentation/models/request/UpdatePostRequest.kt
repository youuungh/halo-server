package com.ninezero.features.social.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdatePostRequest(
    val content: String,
    val tags: List<String>? = null,
    val tagIds: List<Int>? = null,
    val sectionTagId: Int? = null
)
