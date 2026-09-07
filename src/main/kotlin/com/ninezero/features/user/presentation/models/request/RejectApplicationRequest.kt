package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class RejectApplicationRequest(
    val rejectionReason: String
)