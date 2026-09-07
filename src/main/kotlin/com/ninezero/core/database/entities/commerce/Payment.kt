package com.ninezero.core.database.entities.commerce

import com.ninezero.core.common.config.PaymentProvider
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.subscription.SubscriptionTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object PaymentTable : BaseIntIdTable("payments") {
    val orderId = integer("order_id").references(OrderTable.id).nullable()
    val subscriptionId = integer("subscription_id").references(SubscriptionTable.id).nullable()
    val provider = enumerationByName<PaymentProvider>("provider", 50).default(PaymentProvider.MOCK)
    val amount = decimal("amount", 10, 2)
    val status = enumerationByName<PaymentStatus>("status", 50).default(PaymentStatus.PENDING)

    // PG사 결제 정보
    val paymentKey = varchar("payment_key", 200).nullable()
    val transactionId = varchar("transaction_id", 200).nullable()
    val pgProvider = varchar("pg_provider", 50).nullable()
    val receiptUrl = varchar("receipt_url", 500).nullable()
    val method = varchar("method", 50).nullable()
    val approvedAt = varchar("approved_at", 50).nullable()

    // 구독 관련
    val isRenewal = bool("is_renewal").default(false)

    // 환불 정보
    val refundReason = text("refund_reason").nullable()
    val refundAmount = decimal("refund_amount", 10, 2).nullable()
    val refundRequestedAt = datetime("refund_requested_at").nullable()
    val refundCompletedAt = datetime("refund_completed_at").nullable()

    init {
        index(false, orderId)                    // 주문별 결제 조회
        index(false, transactionId)              // PG 웹훅·정산 대사
        index(false, subscriptionId, createdAt)  // 구독 결제 이력
        index(false, status, createdAt)          // PENDING 만료 스윕
    }
}

class PaymentDao(id: EntityID<Int>) : BaseIntEntity(id, PaymentTable) {
    companion object : BaseIntEntityClass<PaymentDao>(PaymentTable)

    var orderId by PaymentTable.orderId
    var subscriptionId by PaymentTable.subscriptionId
    var provider by PaymentTable.provider
    var amount by PaymentTable.amount
    var status by PaymentTable.status
    var paymentKey by PaymentTable.paymentKey
    var transactionId by PaymentTable.transactionId
    var pgProvider by PaymentTable.pgProvider
    var receiptUrl by PaymentTable.receiptUrl
    var method by PaymentTable.method
    var approvedAt by PaymentTable.approvedAt
    var refundReason by PaymentTable.refundReason
    var refundAmount by PaymentTable.refundAmount
    var refundRequestedAt by PaymentTable.refundRequestedAt
    var refundCompletedAt by PaymentTable.refundCompletedAt
    var isRenewal by PaymentTable.isRenewal
}
