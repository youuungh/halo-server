package com.ninezero.features.tag.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdateTagRequest(
    val name: String? = null,
    val isSectionEnabled: Boolean? = null
)
