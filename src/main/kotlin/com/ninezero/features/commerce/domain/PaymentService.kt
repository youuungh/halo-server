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
import com.ninezero.core.common.exception.PaymentResultUnknownException
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.payment.TossCancelResult
import com.ninezero.core.payment.TossConfirmResult
import com.ninezero.core.payment.TossKeys
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.core.payment.TossPaymentResponse
import com.ninezero.core.payment.isDefinitiveDecline
import com.ninezero.features.commerce.data.BillingKeyRepository
import com.ninezero.features.commerce.data.OrderRepository
import com.ninezero.features.commerce.data.PaymentRepository
import com.ninezero.features.commerce.presentation.models.response.TossPrepareResponse
import java.math.BigDecimal

class PaymentService(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val billingKeyRepository: BillingKeyRepository,
    private val orderService: OrderService,
    private val tossPaymentClient: TossPaymentClient
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

    /** Mock 결제 */
    suspend fun processPayment(orderId: Int, userId: Int): String {
        query {
            val order = requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Payment.PAYMENT_PERMISSION_DENIED)

            ValidationUtils.validatePaymentAmount(order.totalPrice)

            val payment = paymentRepository.findByOrderId(orderId)
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            requirePayable(payment.status)

            paymentRepository.updateStatus(payment.id.value, PaymentStatus.COMPLETED)

            orderService.clearOrderedCartItemsInTransaction(orderId, userId)
        }
        orderService.notifyOrderPaid(orderId)  // 커밋 후 알림

        return Messages.Commerce.PAYMENT_SUCCESS
    }

    suspend fun prepareTossPayment(orderId: Int, userId: Int): TossPrepareResponse {
        return query {
            val order = requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Payment.PAYMENT_PERMISSION_DENIED)

            val payment = paymentRepository.findByOrderId(orderId)
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            requirePayable(payment.status)

            val transactionId = "ORDER_${order.orderNumber}_${System.currentTimeMillis()}"
            paymentRepository.updateTransactionId(payment.id.value, transactionId)

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

            // 같은 결제면 멱등 성공
            if (payment.status == PaymentStatus.COMPLETED && payment.paymentKey == paymentKey) return@query null
            requirePayable(payment.status)

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
            is TossConfirmResult.Failure -> {
                // 토스 재조회로 승인 확정 후 실패 처리
                val lookup = runCatching { tossPaymentClient.findPaymentByOrderId(prepared.transactionId) }
                    .onFailure {
                        logger.error(
                            "토스 승인 결과 확인 실패, 미결제 정리 작업이 재확인: orderId={}, error={}",
                            prepared.transactionId, it.message
                        )
                    }
                lookup.getOrNull()?.takeIf { it.status == "DONE" } ?: run {
                    // 승인 여부 불명은 5xx
                    if (isResultUnknown(result, lookup)) throw PaymentResultUnknownException()
                    throw PaymentConfirmFailedException(result.message ?: Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
                }
            }
            is TossConfirmResult.Success -> result.payment
        }

        // 토스 응답 재검증
        if (confirmed.totalAmount != prepared.amount || confirmed.orderId != prepared.transactionId) {
            throw PaymentAmountMismatchException(Errors.Commerce.Payment.TOSS_ORDER_MISMATCH)
        }
        if (confirmed.status != "DONE") {
            throw PaymentConfirmFailedException(Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
        }

        // 승인 중 취소될 수 있어 잠근 뒤 상태 재확인
        val settledStatus = query {
            lockOrder(orderId)  // 동시 취소 차단
            val status = paymentRepository.findById(prepared.paymentId)?.status
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            // 취소된 주문도 환불 근거로 승인 기록
            if (status == PaymentStatus.PENDING || status == PaymentStatus.CANCELLED) {
                paymentRepository.updateTossConfirm(
                    paymentId = prepared.paymentId,
                    paymentKey = confirmed.paymentKey,
                    receiptUrl = confirmed.receipt?.url,
                    method = confirmed.method,
                    approvedAt = confirmed.approvedAt
                )
            }
            if (status == PaymentStatus.PENDING) {
                paymentRepository.updateStatus(prepared.paymentId, PaymentStatus.COMPLETED)
                orderService.clearOrderedCartItemsInTransaction(orderId, userId)
            }
            status
        }

        when (settledStatus) {
            PaymentStatus.PENDING -> orderService.notifyOrderPaid(orderId)  // 커밋 후 알림
            PaymentStatus.COMPLETED -> Unit  // 정리 작업·동시 요청이 먼저 완료
            PaymentStatus.CANCELLED -> {
                refundLateCharge(prepared.paymentId, confirmed.paymentKey, confirmed.totalAmount)
                throw OrderCancelledException(Errors.Commerce.Payment.ORDER_CANCELLED_PAYMENT)
            }
            else -> throw OrderCancelledException(Errors.Commerce.Payment.ORDER_CANCELLED_PAYMENT)  // 환불까지 끝난 주문
        }

        return Messages.Commerce.TOSS_PAYMENT_COMPLETED
    }

    /** 빌링키 원클릭 결제 */
    suspend fun payByBilling(orderId: Int, userId: Int): String {
        val prepared = query {
            val order = requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Payment.PAYMENT_PERMISSION_DENIED)

            val payment = paymentRepository.findByOrderId(orderId)
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            if (payment.status == PaymentStatus.COMPLETED) return@query null  // 멱등 성공
            requirePayable(payment.status)

            val billing = billingKeyRepository.findActiveByUserId(userId)
                ?: throw BillingKeyRequiredException(Errors.Commerce.Payment.BILLING_KEY_REQUIRED)

            // 결과 불명에도 보이게 청구 전 멱등키 기록
            paymentRepository.updateTransactionId(payment.id.value, TossKeys.billingOrderId(order.orderNumber))
            BillingPrepared(
                paymentId = payment.id.value,
                billingKey = billing.billingKey,
                customerKey = billing.customerKey,
                amount = payment.amount.toLong(),
                tossOrderId = TossKeys.billingOrderId(order.orderNumber),
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
                val lookup = runCatching { tossPaymentClient.findPaymentByOrderId(prepared.tossOrderId) }
                    .onFailure {
                        logger.error(
                            "빌링 청구 결과 확인 실패, 미결제 정리 작업이 재확인: orderId={}, error={}",
                            prepared.tossOrderId, it.message
                        )
                    }
                lookup.getOrNull()?.takeIf { it.status == "DONE" } ?: run {
                    // 청구 여부 불명은 5xx
                    if (isResultUnknown(result, lookup)) throw PaymentResultUnknownException()
                    throw PaymentConfirmFailedException(result.message ?: Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
                }
            }
            is TossConfirmResult.Success -> result.payment
        }

        if (confirmed.totalAmount != prepared.amount) {
            throw PaymentAmountMismatchException(Errors.Commerce.Payment.PAYMENT_AMOUNT_MISMATCH)
        }
        if (confirmed.status != "DONE") {
            throw PaymentConfirmFailedException(Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
        }

        // 청구 중 취소될 수 있어 잠근 뒤 상태 재확인
        val settledStatus = query {
            lockOrder(orderId)  // 동시 취소 차단
            val status = paymentRepository.findById(prepared.paymentId)?.status
                ?: throw PaymentNotFoundException(Errors.Commerce.Payment.PAYMENT_NOT_FOUND)

            // 취소된 주문도 환불 근거로 청구 기록
            if (status == PaymentStatus.PENDING || status == PaymentStatus.CANCELLED) {
                paymentRepository.updateTransactionId(prepared.paymentId, prepared.tossOrderId)
                paymentRepository.updatePaymentInfo(
                    paymentId = prepared.paymentId,
                    paymentKey = confirmed.paymentKey,
                    receiptUrl = confirmed.receipt?.url
                )
            }
            if (status == PaymentStatus.PENDING) {
                paymentRepository.updateStatus(prepared.paymentId, PaymentStatus.COMPLETED)
                orderService.clearOrderedCartItemsInTransaction(orderId, userId)
            }
            status
        }

        when (settledStatus) {
            PaymentStatus.PENDING -> orderService.notifyOrderPaid(orderId)  // 커밋 후 알림
            PaymentStatus.COMPLETED -> Unit  // 같은 멱등키 동시 요청이 먼저 완료
            PaymentStatus.CANCELLED -> {
                refundLateCharge(prepared.paymentId, confirmed.paymentKey, confirmed.totalAmount)
                throw OrderCancelledException(Errors.Commerce.Payment.ORDER_CANCELLED_PAYMENT)
            }
            else -> throw OrderCancelledException(Errors.Commerce.Payment.ORDER_CANCELLED_PAYMENT)  // 환불까지 끝난 주문
        }

        return Messages.Commerce.PAYMENT_SUCCESS
    }

    /** 청구·승인 중 취소된 주문의 늦은 결제 전액 환불 */
    private suspend fun refundLateCharge(paymentId: Int, paymentKey: String, amount: Long) {
        val result = tossPaymentClient.cancel(
            paymentKey = paymentKey,
            cancelReason = TossKeys.CANCEL_REASON,
            cancelAmount = null,  // 전액 취소
            idempotencyKey = TossKeys.cancelKey(paymentId)
        )
        if (result is TossCancelResult.Failure) {
            logger.error(
                "취소된 주문의 늦은 결제 환불 실패 — 토스 대시보드 수동 환불 필요: paymentId={}, code={}, message={}",
                paymentId, result.code, result.message
            )
            return
        }
        query {
            paymentRepository.requestRefund(paymentId, TossKeys.CANCEL_REASON, BigDecimal(amount))
            paymentRepository.completeRefund(paymentId, nowUtc())
        }
    }

    /** 승인·청구 성사 여부를 알 수 없는 실패 */
    private fun isResultUnknown(
        result: TossConfirmResult.Failure,
        lookup: Result<TossPaymentResponse?>
    ): Boolean = result.httpStatus == 0 || result.httpStatus >= 500 || lookup.isFailure

    /** 결제 대기 주문만 결제 */
    private fun requirePayable(status: PaymentStatus) {
        when (status) {
            PaymentStatus.PENDING -> Unit
            PaymentStatus.COMPLETED -> throw OrderAlreadyPaidException(Errors.Commerce.Payment.ORDER_ALREADY_PAID)
            else -> throw OrderCancelledException(Errors.Commerce.Payment.ORDER_CANCELLED_PAYMENT)  // 취소·환불된 주문
        }
    }
}
