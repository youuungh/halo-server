package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.exception.BillingKeyRequiredException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.OrderAlreadyPaidException
import com.ninezero.core.common.exception.OrderCancelledException
import com.ninezero.core.common.exception.PaymentAmountMismatchException
import com.ninezero.core.common.exception.PaymentConfirmFailedException
import com.ninezero.core.common.exception.PaymentNotFoundException
import com.ninezero.core.common.exception.RefundNotAllowedException
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.payment.TossConfirmResult
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.core.payment.isDefinitiveDecline
import com.ninezero.features.commerce.data.BillingKeyRepository
import com.ninezero.features.commerce.data.CartRepository
import com.ninezero.features.commerce.data.OrderRepository
import com.ninezero.features.commerce.data.PaymentRepository
import com.ninezero.features.commerce.presentation.models.response.TossPrepareResponse
import java.math.BigDecimal

class PaymentService(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val tossPaymentClient: TossPaymentClient,
    private val cartRepository: CartRepository,
    private val billingKeyRepository: BillingKeyRepository
) {
    private val logger = logger()

    private data class PreparedConfirm(
        val paymentId: Int,
        val transactionId: String,
        val amount: Long
    )

    private data class BillingPrepared(
        val paymentId: Int,
        val billingKey: String,
        val customerKey: String,
        val amount: Long,
        val tossOrderId: String,
        val orderNumber: String
    )

    private suspend fun clearOrderedCartItems(orderId: Int, userId: Int) {
        val productIds = orderRepository.findOrderItems(orderId).map { it.productId }.toSet()  // 결제 확정 시 비움
        if (productIds.isEmpty()) return
        val cartIds = cartRepository.findUserCart(userId)
            .filter { it.productId in productIds }
            .map { it.id.value }
        if (cartIds.isNotEmpty()) {
            cartRepository.deleteCartItems(cartIds)
        }
    }

    /** Mock 결제 */
    suspend fun processPayment(orderId: Int, userId: Int): String {
        query {
            val order = requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Payment.PAYMENT_PERMISSION_DENIED)

            ValidationUtils.validatePaymentAmount(order.totalPrice)

            val payment = paymentRepository.findByOrderId(orderId)
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            if (payment.status == PaymentStatus.COMPLETED) {  // 이미 결제완료면 예외
                throw OrderAlreadyPaidException(Errors.Commerce.Payment.ORDER_ALREADY_PAID)
            }

            if (payment.status == PaymentStatus.CANCELLED) {
                throw OrderCancelledException(Errors.Commerce.Payment.ORDER_CANCELLED_PAYMENT)
            }

            paymentRepository.updateStatus(payment.id.value, PaymentStatus.COMPLETED)

            clearOrderedCartItems(orderId, userId)
        }

        return Messages.Commerce.PAYMENT_SUCCESS
    }

    /** Mock 환불 */
    suspend fun refundPayment(orderId: Int, userId: Int): String {
        query {
            requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Payment.REFUND_PERMISSION_DENIED)

            val payment = paymentRepository.findByOrderId(orderId)
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            if (payment.status != PaymentStatus.COMPLETED) {
                throw RefundNotAllowedException(Errors.Commerce.Payment.REFUND_ONLY_PAID_ORDERS)
            }

            paymentRepository.requestRefund(
                paymentId = payment.id.value,
                refundReason = "사용자 요청",
                refundAmount = payment.amount
            )

            paymentRepository.completeRefund(
                paymentId = payment.id.value,
                refundCompletedAt = nowUtc()
            )
        }

        return Messages.Commerce.REFUND_SUCCESS
    }

    suspend fun getPaymentStatus(orderId: Int, userId: Int): PaymentStatus {
        return query {
            requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Payment.PAYMENT_STATUS_PERMISSION_DENIED)

            val payment = paymentRepository.findByOrderId(orderId)
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            payment.status
        }
    }

    suspend fun prepareTossPayment(orderId: Int, userId: Int): TossPrepareResponse {
        return query {
            val order = requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Payment.PAYMENT_PERMISSION_DENIED)

            val payment = paymentRepository.findByOrderId(orderId)
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            if (payment.status == PaymentStatus.COMPLETED) {  // 이미 결제완료면 예외
                throw OrderAlreadyPaidException(Errors.Commerce.Payment.ORDER_ALREADY_PAID)
            }

            val transactionId = "ORDER_${order.orderNumber}_${System.currentTimeMillis()}"
            paymentRepository.updateTransactionId(payment.id.value, transactionId)
            paymentRepository.updatePaymentInfo(
                paymentId = payment.id.value,
                paymentKey = null,
                pgProvider = "TOSS_PAYMENTS",
                receiptUrl = null
            )

            // amount는 .00 없는 정수 문자열로 반환
            TossPrepareResponse(
                orderId = transactionId,
                orderName = "주문 #${order.orderNumber}",
                amount = order.totalPrice.toAmountString(),
                customerName = order.shippingName
            )
        }
    }

    suspend fun confirmTossPayment(
        orderId: Int,
        paymentKey: String,
        amount: Long,
        userId: Int
    ): String {
        val prepared = query {  // 앱 → 서버 → 토스 경로로만 확정
            requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Payment.PAYMENT_PERMISSION_DENIED)

            val payment = paymentRepository.findByOrderId(orderId)
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            when (payment.status) {
                PaymentStatus.COMPLETED -> {
                    // 같은 결제면 멱등 성공
                    if (payment.paymentKey == paymentKey) return@query null
                    throw OrderAlreadyPaidException(Errors.Commerce.Payment.ORDER_ALREADY_PAID)
                }
                PaymentStatus.CANCELLED ->
                    throw OrderCancelledException(Errors.Commerce.Payment.ORDER_CANCELLED_PAYMENT)
                else -> Unit
            }

            val transactionId = payment.transactionId
                ?: throw PaymentConfirmFailedException(Errors.Commerce.Payment.PAYMENT_NOT_PREPARED)

            // 클라이언트 금액이 서버 기록과 다르면 거부
            if (payment.amount.compareTo(BigDecimal(amount)) != 0) {
                throw PaymentAmountMismatchException(Errors.Commerce.Payment.PAYMENT_AMOUNT_MISMATCH)
            }

            PreparedConfirm(payment.id.value, transactionId, payment.amount.toLong())
        } ?: return Messages.Commerce.TOSS_PAYMENT_COMPLETED

        // 토스 승인
        val confirmed = when (
            val result = tossPaymentClient.confirm(
                paymentKey = paymentKey,
                orderId = prepared.transactionId,
                amount = prepared.amount,
                idempotencyKey = prepared.transactionId
            )
        ) {
            is TossConfirmResult.Failure ->
                throw PaymentConfirmFailedException(result.message ?: Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
            is TossConfirmResult.Success -> result.payment
        }

        // 토스 응답 재검증
        if (confirmed.totalAmount != prepared.amount || confirmed.orderId != prepared.transactionId) {
            throw PaymentAmountMismatchException(Errors.Commerce.Payment.TOSS_ORDER_MISMATCH)
        }
        if (confirmed.status != "DONE") {
            throw PaymentConfirmFailedException(Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
        }

        // 결제 정보 저장 + 완료 처리
        query {
            paymentRepository.updateTossConfirm(
                paymentId = prepared.paymentId,
                paymentKey = confirmed.paymentKey,
                receiptUrl = confirmed.receipt?.url,
                method = confirmed.method,
                approvedAt = confirmed.approvedAt
            )
            paymentRepository.updateStatus(prepared.paymentId, PaymentStatus.COMPLETED)

            clearOrderedCartItems(orderId, userId)
        }

        return Messages.Commerce.TOSS_PAYMENT_COMPLETED
    }

    /** 빌링키 원클릭 결제 */
    suspend fun payByBilling(orderId: Int, userId: Int): String {
        val prepared = query {
            val order = requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Payment.PAYMENT_PERMISSION_DENIED)

            val payment = paymentRepository.findByOrderId(orderId)
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            when (payment.status) {
                PaymentStatus.COMPLETED -> return@query null // 멱등 성공
                PaymentStatus.CANCELLED ->
                    throw OrderCancelledException(Errors.Commerce.Payment.ORDER_CANCELLED_PAYMENT)
                else -> Unit
            }

            val billing = billingKeyRepository.findActiveByUserId(userId)
                ?: throw BillingKeyRequiredException("등록된 결제수단이 없습니다.")

            // 빌링 토스 orderId는 주문번호 기반 멱등키
            BillingPrepared(
                paymentId = payment.id.value,
                billingKey = billing.billingKey,
                customerKey = billing.customerKey,
                amount = payment.amount.toLong(),
                tossOrderId = "ORDER_${order.orderNumber}",
                orderNumber = order.orderNumber
            )
        } ?: return Messages.Commerce.PAYMENT_SUCCESS

        val confirmed = when (
            val result = tossPaymentClient.chargeBilling(
                billingKey = prepared.billingKey,
                customerKey = prepared.customerKey,
                amount = prepared.amount,
                orderId = prepared.tossOrderId,
                orderName = "주문 #${prepared.orderNumber}",
                idempotencyKey = prepared.tossOrderId
            )
        ) {
            is TossConfirmResult.Failure -> {
                if (result.isDefinitiveDecline) {
                    throw PaymentConfirmFailedException(result.message ?: Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
                }
                // 토스 재조회로 청구 확정 후 실패 처리
                runCatching { tossPaymentClient.findPaymentByOrderId(prepared.tossOrderId) }
                    .onFailure {
                        logger.error(
                            "빌링 청구 결과 확인 실패 — 토스 대시보드 수동 확인 필요: orderId={}, error={}",
                            prepared.tossOrderId, it.message
                        )
                    }
                    .getOrNull()
                    ?.takeIf { it.status == "DONE" }
                    ?: throw PaymentConfirmFailedException(result.message ?: Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
            }
            is TossConfirmResult.Success -> result.payment
        }

        if (confirmed.totalAmount != prepared.amount) {
            throw PaymentAmountMismatchException(Errors.Commerce.Payment.PAYMENT_AMOUNT_MISMATCH)
        }
        if (confirmed.status != "DONE") {
            throw PaymentConfirmFailedException(Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
        }

        query {
            paymentRepository.updateTransactionId(prepared.paymentId, prepared.tossOrderId)
            paymentRepository.updatePaymentInfo(
                paymentId = prepared.paymentId,
                paymentKey = confirmed.paymentKey,
                pgProvider = "TOSS_BILLING",
                receiptUrl = confirmed.receipt?.url
            )
            paymentRepository.updateStatus(prepared.paymentId, PaymentStatus.COMPLETED)
            clearOrderedCartItems(orderId, userId)
        }

        return Messages.Commerce.PAYMENT_SUCCESS
    }
}
