package com.ninezero.features.point.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class PointHistoryResponse(
    val id: Int,
    val type: String,
    val amount: String,
    val balanceBefore: String,
    val balanceAfter: String,
    val orderId: Int?,
    val description: String,
    val expiresAt: LocalDateTime?,
    val createdAt: LocalDateTime
)
