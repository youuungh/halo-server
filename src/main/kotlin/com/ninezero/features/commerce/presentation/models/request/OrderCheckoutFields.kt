package com.ninezero.features.commerce.presentation.models.request

import com.ninezero.core.common.config.PaymentProvider

/** 장바구니 주문·바로구매 공통 배송·결제 입력 */
interface OrderCheckoutFields {
    val shippingAddress: String
    val shippingPhone: String
    val shippingName: String
    val paymentProvider: PaymentProvider
    val memo: String?
    val pointsToUse: String?
    val couponCodes: List<String>?
}
