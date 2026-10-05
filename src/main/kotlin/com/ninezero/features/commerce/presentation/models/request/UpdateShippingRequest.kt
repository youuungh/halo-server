package com.ninezero.features.commerce.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdateShippingRequest(
    val trackingNumber: String,
    val carrier: String
)
