package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class ReportResponse(
    val reportCount: Int,
    val blinded: Boolean
)
