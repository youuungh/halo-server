package com.ninezero.features.commerce.presentation.models.request

import com.ninezero.core.common.config.PaymentProvider
import kotlinx.serialization.Serializable

/** 장바구니를 거치지 않는 바로구매 주문 */
@Serializable
data class DirectOrderRequest(
    val productId: Int,
    val quantity: Int,
    override val shippingAddress: String,
    override val shippingPhone: String,
    override val shippingName: String,
    override val paymentProvider: PaymentProvider,
    override val memo: String? = null,
    override val pointsToUse: String? = null,
    override val couponCodes: List<String>? = null
) : OrderCheckoutFields
