package com.ninezero.features.user.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class AddressResponse(
    val id: Int,
    val recipientName: String,
    val recipientPhone: String,
    val zipCode: String,
    val address: String,
    val addressDetail: String?,
    val memo: String?,
    val isDefault: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
