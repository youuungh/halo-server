package com.ninezero.features.point.presentation.models.response

import com.ninezero.core.common.util.PaginatedResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class PointHistoryResponse(
    val id: Int,
    val type: String,
    val amount: String,
    val balanceBefore: String,
    val balanceAfter: String,
    val orderId: Int? = null,
    val description: String,
    val expiresAt: LocalDateTime? = null,
    val createdAt: LocalDateTime
)

typealias PointHistoryListResponse = PaginatedResponse<PointHistoryResponse>
