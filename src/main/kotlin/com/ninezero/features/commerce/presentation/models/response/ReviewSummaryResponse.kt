package com.ninezero.features.commerce.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class ReviewSummaryResponse(
    val productId: Int,
    val avgRating: Double,
    val totalReviews: Int,
    val ratingDistribution: Map<Int, Int>
)
