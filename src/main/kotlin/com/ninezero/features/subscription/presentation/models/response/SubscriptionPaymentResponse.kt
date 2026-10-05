package com.ninezero.features.subscription.presentation.models.response

import com.ninezero.core.common.config.PaymentProvider
import com.ninezero.core.common.config.PaymentStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionPaymentResponse(
    val paymentId: Int,
    val amount: String,
    val status: PaymentStatus,
    val provider: PaymentProvider,
    val isRenewal: Boolean,
    val receiptUrl: String? = null,
    val createdAt: LocalDateTime
)
