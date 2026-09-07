package com.ninezero.features.commerce.presentation.models.response

import com.ninezero.features.tag.presentation.models.response.TagResponse
import kotlinx.serialization.Serializable

@Serializable
data class ProductTagSectionResponse(
    val tag: TagResponse,
    val products: List<ProductResponse>,
    val hasMore: Boolean
)
