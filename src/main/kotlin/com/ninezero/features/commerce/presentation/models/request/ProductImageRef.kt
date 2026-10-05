package com.ninezero.features.commerce.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class ProductImageRef(
    val url: String? = null,
    val draftId: Int? = null
)
