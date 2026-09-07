package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class CreatorApplicationRequest(
    val reason: String,
    val portfolioUrl: String? = null
)