package com.ninezero.features.commerce.presentation.models.request

import com.ninezero.core.common.config.PaymentProvider
import kotlinx.serialization.Serializable

@Serializable
data class OrderRequest(
    val shippingAddress: String,
    val shippingPhone: String,
    val shippingName: String,
    val paymentProvider: PaymentProvider,
    val memo: String? = null,
    val pointsToUse: String? = null,
    val couponCodes: List<String>? = null,
    val cartItemIds: List<Int>? = null
)
