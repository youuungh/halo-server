package com.ninezero.features.subscription.domain

import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.PaymentProvider
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.exception.BillingKeyRequiredException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.PaymentConfirmFailedException
import com.ninezero.core.common.exception.SubscriptionNotFoundException
import com.ninezero.core.common.exception.SubscriptionPlanNotFoundException
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.payment.TossConfirmResult
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.core.payment.TossPaymentResponse
import com.ninezero.core.payment.isDefinitiveDecline
import com.ninezero.features.commerce.data.BillingKeyRepository
import com.ninezero.features.commerce.data.PaymentRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import kotlinx.datetime.number
import java.math.BigDecimal

/** 갱신 청구 판정 */
enum class RenewalResult { SUCCESS, DECLINED, UNKNOWN }

class SubscriptionPaymentService(
    private val paymentRepository: PaymentRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val billingKeyRepository: BillingKeyRepository,
    private val tossPaymentClient: TossPaymentClient
) {
    private val logger = logger()

    private data class ChargeContext(
        val amount: BigDecimal,
        val planName: String,
        val billingKey: String,
        val customerKey: String
    )

    suspend fun processInitialPayment(
        subscriptionId: Int,
        userId: Int
    ): String {
        val ctx = query {  // Mock 아님
            val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                ?: throw SubscriptionNotFoundException(subscriptionId)

            if (subscription.userId != userId) {
                throw ForbiddenException(Errors.Common.PERMISSION_DENIED)
            }

            val plan = planRepository.findPlanById(subscription.planId)
                ?: throw SubscriptionPlanNotFoundException(subscription.planId)

            val billing = billingKeyRepository.findActiveByUserId(userId)
                ?: throw BillingKeyRequiredException("등록된 결제수단이 없습니다. 카드를 먼저 등록해주세요.")

            ChargeContext(plan.price, plan.name, billing.billingKey, billing.customerKey)
        }

        val orderId = generateNumericOrderId()
        val confirmed = charge(ctx, orderId, "구독 - ${ctx.planName}")

        query {
            recordSubPayment(subscriptionId, ctx.amount, isRenewal = false, orderId = orderId, confirmed = confirmed)
        }

        return Messages.Commerce.PAYMENT_SUCCESS
    }

    suspend fun processRenewalPayment(subscriptionId: Int): RenewalResult {
        val captured = try {  // DECLINED만 실패, UNKNOWN은 재시도
            query {
                val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                    ?: return@query null
                val plan = planRepository.findPlanById(subscription.planId)
                    ?: return@query null
                val billing = billingKeyRepository.findActiveByUserId(subscription.userId)
                    ?: return@query null
                ChargeContext(plan.price, plan.name, billing.billingKey, billing.customerKey) to
                    subscription.expiresAt.date.toString()
            }
        } catch (e: Exception) {
            logger.error("갱신 결제 준비 실패(다음 주기 재시도): subscriptionId={}, error={}", subscriptionId, e.message)
            return RenewalResult.UNKNOWN
        } ?: return RenewalResult.DECLINED  // 재시도 무의미

        val (ctx, period) = captured
        // 멱등키
        val orderId = "$subscriptionId${period.replace("-", "")}"
        val confirmed = when (
            val result = tossPaymentClient.chargeBilling(
                billingKey = ctx.billingKey,
                customerKey = ctx.customerKey,
                amount = ctx.amount.toLong(),
                orderId = orderId,
                orderName = "구독 갱신 - ${ctx.planName}",
                idempotencyKey = orderId
            )
        ) {
            is TossConfirmResult.Failure ->
                return if (result.isDefinitiveDecline) RenewalResult.DECLINED else RenewalResult.UNKNOWN
            is TossConfirmResult.Success -> result.payment
        }
        if (confirmed.status != "DONE") return RenewalResult.DECLINED

        return try {
            query {
                recordSubPayment(subscriptionId, ctx.amount, isRenewal = true, orderId = orderId, confirmed = confirmed)
            }
            RenewalResult.SUCCESS
        } catch (e: Exception) {
            // 기록 실패는 재시도
            logger.error("갱신 결제 기록 실패(다음 주기 재시도): subscriptionId={}, orderId={}, error={}", subscriptionId, orderId, e.message)
            RenewalResult.UNKNOWN
        }
    }

    suspend fun chargeUpgrade(
        subscriptionId: Int,
        userId: Int,
        newPlanId: Int,
        diffAmount: BigDecimal,
        planName: String
    ): String {
        val ctx = query {
            val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                ?: throw SubscriptionNotFoundException(subscriptionId)
            if (subscription.userId != userId) {
                throw ForbiddenException(Errors.Common.PERMISSION_DENIED)
            }
            val billing = billingKeyRepository.findActiveByUserId(userId)
                ?: throw BillingKeyRequiredException("등록된 결제수단이 없습니다. 카드를 먼저 등록해주세요.")
            ChargeContext(diffAmount, planName, billing.billingKey, billing.customerKey)
        }

        val orderId = generateNumericOrderId()
        val confirmed = charge(ctx, orderId, "구독 업그레이드 - ${ctx.planName}")

        query {
            recordSubPayment(subscriptionId, diffAmount, isRenewal = false, orderId = orderId, confirmed = confirmed)
        }

        return Messages.Commerce.PAYMENT_SUCCESS
    }

    private fun generateNumericOrderId(): String {
        val now = nowUtc()
        val dateStr = "${now.year}${now.month.number.toString().padStart(2, '0')}${now.day.toString().padStart(2, '0')}"
        val randomStr = (10_000_000..99_999_999).random()  // 날짜8 + 랜덤8 숫자
        return "$dateStr$randomStr"
    }

    /** 토스 빌링 청구 */
    private suspend fun charge(ctx: ChargeContext, orderId: String, orderName: String): TossPaymentResponse {
        val result = tossPaymentClient.chargeBilling(
            billingKey = ctx.billingKey,
            customerKey = ctx.customerKey,
            amount = ctx.amount.toLong(),
            orderId = orderId,
            orderName = orderName,
            idempotencyKey = orderId
        )
        val confirmed = when (result) {
            is TossConfirmResult.Failure -> {
                if (result.isDefinitiveDecline) {
                    throw PaymentConfirmFailedException(result.message ?: Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
                }
                resolveUnknownCharge(orderId)  // 재시도 이중 청구 방지
                    ?: throw PaymentConfirmFailedException(result.message ?: Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
            }
            is TossConfirmResult.Success -> result.payment
        }
        if (confirmed.status != "DONE") {
            throw PaymentConfirmFailedException(Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
        }
        return confirmed
    }

    /** 결과 불명 청구 확정 */
    private suspend fun resolveUnknownCharge(orderId: String): TossPaymentResponse? {
        return try {
            tossPaymentClient.findPaymentByOrderId(orderId)?.takeIf { it.status == "DONE" }  // DONE만 성공, 그 외 null
        } catch (e: Exception) {
            logger.error("구독 청구 결과 확인 실패 — 토스 대시보드 수동 확인 필요: orderId={}, error={}", orderId, e.message)
            null
        }
    }

    private suspend fun recordSubPayment(
        subscriptionId: Int,
        amount: BigDecimal,
        isRenewal: Boolean,
        orderId: String,
        confirmed: TossPaymentResponse
    ) {
        val payment = paymentRepository.createSubPayment(
            subscriptionId = subscriptionId,
            provider = PaymentProvider.TOSS_BILLING,
            amount = amount,
            isRenewal = isRenewal,
            transactionId = orderId
        )
        paymentRepository.updatePaymentInfo(
            paymentId = payment.id.value,
            paymentKey = confirmed.paymentKey,
            pgProvider = PaymentProvider.TOSS_BILLING.name,
            receiptUrl = confirmed.receipt?.url
        )
        paymentRepository.updateStatus(payment.id.value, PaymentStatus.COMPLETED)
    }

    suspend fun getPaymentHistory(
        subscriptionId: Int,
        userId: Int
    ): List<Map<String, Any>> {
        val payments = query {
            val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                ?: throw SubscriptionNotFoundException(subscriptionId)

            if (subscription.userId != userId) {
                throw ForbiddenException(Errors.Common.PERMISSION_DENIED)
            }

            paymentRepository.findAllBySubscriptionId(subscriptionId)
        }

        return payments.map { payment ->
            mapOf<String, Any>(
                "paymentId" to payment.id.value,
                "amount" to payment.amount.toAmountString(),
                "status" to payment.status.name,
                "provider" to payment.provider.name,
                "isRenewal" to payment.isRenewal,
                "createdAt" to payment.createdAt.toString(),
                "paymentKey" to (payment.paymentKey ?: ""),
                "receiptUrl" to (payment.receiptUrl ?: "")
            )
        }
    }
}
