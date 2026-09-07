package com.ninezero.features.commerce.data

import com.ninezero.core.common.config.PaymentProvider
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.database.entities.commerce.PaymentDao
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

interface PaymentRepository {

    // 결제 생성
    suspend fun createPayment(
        orderId: Int,
        provider: PaymentProvider,
        amount: BigDecimal,
        transactionId: String? = null
    ): PaymentDao

    // 결제 조회
    suspend fun findById(id: Int): PaymentDao?
    suspend fun findByOrderId(orderId: Int): PaymentDao?

    suspend fun findStalePendingPaymentOrderIds(before: LocalDateTime): List<Int>
    suspend fun findByOrderIds(orderIds: List<Int>): Map<Int, PaymentDao>

    // 결제 상태 수정
    suspend fun updateStatus(paymentId: Int, status: PaymentStatus): Boolean
    suspend fun updateTransactionId(paymentId: Int, transactionId: String): Boolean
    suspend fun updatePaymentInfo(
        paymentId: Int,
        paymentKey: String?,
        pgProvider: String?,
        receiptUrl: String?
    ): Boolean
    suspend fun updateTossConfirm(
        paymentId: Int,
        paymentKey: String,
        receiptUrl: String?,
        method: String?,
        approvedAt: String?
    ): Boolean

    // 환불
    suspend fun requestRefund(paymentId: Int, refundReason: String, refundAmount: BigDecimal): Boolean
    suspend fun completeRefund(paymentId: Int, refundCompletedAt: LocalDateTime): Boolean

    // 구독 결제 생성/조회
    suspend fun createSubPayment(
        subscriptionId: Int,
        provider: PaymentProvider,
        amount: BigDecimal,
        isRenewal: Boolean = false,
        transactionId: String? = null
    ): PaymentDao

    suspend fun findAllBySubscriptionId(subscriptionId: Int): List<PaymentDao>
}
