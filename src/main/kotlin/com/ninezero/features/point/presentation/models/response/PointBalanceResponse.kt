package com.ninezero.features.point.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class PointBalanceResponse(
    val balance: String,
    val totalEarned: String,
    val totalUsed: String,
    val totalExpired: String,
    val expiringPoints: String,
    val expiresAt: LocalDateTime? = null
)
