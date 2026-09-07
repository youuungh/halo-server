package com.ninezero.features.commerce.presentation.models.response

import com.ninezero.core.common.util.PaginatedResponse
import kotlinx.serialization.Serializable

@Serializable
data class ReviewListResponse(
    val reviews: PaginatedResponse<ReviewResponse>,
    val avgRating: Double,
    val totalReviews: Int
)
