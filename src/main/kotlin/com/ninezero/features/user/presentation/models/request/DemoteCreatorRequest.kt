package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class DemoteCreatorRequest(
    val reason: String
)
