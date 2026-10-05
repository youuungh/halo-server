package com.ninezero.features.user.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class CreateAddressRequest(
    val recipientName: String,
    val recipientPhone: String,
    val zipCode: String,
    val address: String,
    val addressDetail: String? = null,
    val memo: String? = null,
    val isDefault: Boolean = false
)
