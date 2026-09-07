package com.ninezero.features.commerce.data

import com.ninezero.core.common.config.PaymentProvider
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.database.entities.commerce.PaymentDao
import com.ninezero.core.database.entities.commerce.PaymentTable
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import java.math.BigDecimal

class PaymentRepositoryImpl : PaymentRepository {

    /** 주문 결제 생성 */
    override suspend fun createPayment(
        orderId: Int,
        provider: PaymentProvider,
        amount: BigDecimal,
        transactionId: String?
    ): PaymentDao {
        return PaymentDao.new {
            this.orderId = orderId
            this.provider = provider
            this.amount = amount
            this.status = PaymentStatus.PENDING  // 초기 상태
            this.transactionId = transactionId
        }
    }

    /** 결제 조회 */
    override suspend fun findById(id: Int): PaymentDao? {
        return PaymentDao.findById(id)
    }

    /** 주문의 결제 조회 */
    override suspend fun findByOrderId(orderId: Int): PaymentDao? {
        return PaymentDao.find { PaymentTable.orderId eq orderId }.singleOrNull()  // 2건 이상이면 예외
    }

    /** 방치된 PENDING 결제의 orderId 목록 */
    override suspend fun findStalePendingPaymentOrderIds(before: LocalDateTime): List<Int> {
        return PaymentDao.find {
            (PaymentTable.status eq PaymentStatus.PENDING) and (PaymentTable.createdAt less before)
        }.mapNotNull { it.orderId }  // 구독결제는 orderId null이라 자동 제외
    }

    /** 여러 주문의 결제 일괄 조회 */
    override suspend fun findByOrderIds(orderIds: List<Int>): Map<Int, PaymentDao> {
        if (orderIds.isEmpty()) return emptyMap()

        return PaymentDao.find { PaymentTable.orderId inList orderIds }
            .associateBy { it.orderId!! }
    }

    /** 결제 status 교체 */
    override suspend fun updateStatus(
        paymentId: Int,
        status: PaymentStatus
    ): Boolean {
        val payment = PaymentDao.findById(paymentId) ?: return false
        payment.status = status
        return true
    }

    /** 결제 transactionId 교체 */
    override suspend fun updateTransactionId(paymentId: Int, transactionId: String): Boolean {
        val payment = PaymentDao.findById(paymentId) ?: return false
        payment.transactionId = transactionId
        return true
    }

    /** 결제 PG 정보 부분 수정 */
    override suspend fun updatePaymentInfo(
        paymentId: Int,
        paymentKey: String?,
        pgProvider: String?,
        receiptUrl: String?
    ): Boolean {
        val payment = PaymentDao.findById(paymentId) ?: return false

        paymentKey?.let { payment.paymentKey = it }  // null은 유지
        pgProvider?.let { payment.pgProvider = it }
        receiptUrl?.let { payment.receiptUrl = it }

        return true
    }

    /** 토스 승인 결과 반영 */
    override suspend fun updateTossConfirm(
        paymentId: Int,
        paymentKey: String,
        receiptUrl: String?,
        method: String?,
        approvedAt: String?
    ): Boolean {
        val payment = PaymentDao.findById(paymentId) ?: return false

        payment.provider = PaymentProvider.TOSS_PAYMENTS  // TOSS_PAYMENTS로 확정
        payment.paymentKey = paymentKey
        payment.pgProvider = PaymentProvider.TOSS_PAYMENTS.name
        receiptUrl?.let { payment.receiptUrl = it }  // null이면 유지
        method?.let { payment.method = it }
        approvedAt?.let { payment.approvedAt = it }

        return true
    }

    /** 환불 요청 기록 */
    override suspend fun requestRefund(
        paymentId: Int,
        refundReason: String,
        refundAmount: BigDecimal
    ): Boolean {
        val payment = PaymentDao.findById(paymentId) ?: return false

        payment.status = PaymentStatus.REFUND_PENDING  // REFUND_PENDING 전환
        payment.refundReason = refundReason
        payment.refundAmount = refundAmount
        payment.refundRequestedAt = nowUtc()

        return true
    }

    /** 환불 완료 기록 */
    override suspend fun completeRefund(
        paymentId: Int,
        refundCompletedAt: LocalDateTime
    ): Boolean {
        val payment = PaymentDao.findById(paymentId) ?: return false

        payment.status = PaymentStatus.REFUNDED  // REFUNDED 전환
        payment.refundCompletedAt = refundCompletedAt

        return true
    }

    /** 구독 결제 생성 */
    override suspend fun createSubPayment(
        subscriptionId: Int,
        provider: PaymentProvider,
        amount: BigDecimal,
        isRenewal: Boolean,
        transactionId: String?
    ): PaymentDao {
        return PaymentDao.new {
            this.subscriptionId = subscriptionId  // orderId 없이 구독으로 연결
            this.provider = provider
            this.amount = amount
            this.status = PaymentStatus.PENDING  // 초기 상태
            this.isRenewal = isRenewal
            this.transactionId = transactionId
        }
    }

    /** 구독의 결제 이력 전체 조회 */
    override suspend fun findAllBySubscriptionId(subscriptionId: Int): List<PaymentDao> {
        return PaymentDao.find { PaymentTable.subscriptionId eq subscriptionId }
            .orderBy(PaymentTable.createdAt to SortOrder.DESC, PaymentTable.id to SortOrder.DESC)  // 최신순
            .toList()
    }
}
