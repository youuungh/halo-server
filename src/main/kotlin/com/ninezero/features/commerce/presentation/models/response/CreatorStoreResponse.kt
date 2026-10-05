package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CreatorStoreResponse(
    val tagSections: List<ProductTagSectionResponse>,
    val allProducts: ProductListResponse
)
