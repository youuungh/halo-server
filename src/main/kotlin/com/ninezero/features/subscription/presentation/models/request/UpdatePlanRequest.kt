package com.ninezero.features.subscription.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdatePlanRequest(
    val name: String? = null,
    val description: String? = null,
    val price: String? = null,
    val benefits: List<String>? = null,
    val isActive: Boolean? = null
)
