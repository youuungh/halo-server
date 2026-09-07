package com.ninezero.features.commerce.presentation.models.request

import com.ninezero.core.common.config.ShippingStatus
import kotlinx.serialization.Serializable

@Serializable
data class UpdateShippingStatusRequest(
    val status: ShippingStatus
)
