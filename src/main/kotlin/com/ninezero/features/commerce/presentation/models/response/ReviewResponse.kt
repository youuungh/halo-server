package com.ninezero.features.commerce.presentation.models.response

import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class ReviewResponse(
    val id: Int,
    val user: UserSummaryResponse,
    val productId: Int,
    val orderId: Int,
    val rating: Int,
    val content: String,
    val images: List<String>,
    val productName: String? = null,
    val productImageUrl: String? = null,
    val canAccess: Boolean = true,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
