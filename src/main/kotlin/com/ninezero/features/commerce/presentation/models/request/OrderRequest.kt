package com.ninezero.features.commerce.presentation.models.request

import com.ninezero.core.common.config.PaymentProvider
import kotlinx.serialization.Serializable

@Serializable
data class OrderRequest(
    override val shippingAddress: String,
    override val shippingPhone: String,
    override val shippingName: String,
    override val paymentProvider: PaymentProvider,
    override val memo: String? = null,
    override val pointsToUse: String? = null,
    override val couponCodes: List<String>? = null,
    val cartItemIds: List<Int>? = null
) : OrderCheckoutFields
