package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class LogSourceResponse(
    val name: String,
    val count: Int
)
