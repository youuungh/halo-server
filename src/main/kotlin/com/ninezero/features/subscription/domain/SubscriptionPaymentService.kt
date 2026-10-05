package com.ninezero.features.subscription.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.PaymentProvider
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.config.SubscriptionStatus
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.payment.TossConfirmResult
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.core.payment.TossPaymentResponse
import com.ninezero.core.payment.isDefinitiveDecline
import com.ninezero.features.commerce.data.BillingKeyRepository
import com.ninezero.features.commerce.data.PaymentRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.subscription.presentation.models.response.SubscriptionPaymentResponse
import com.ninezero.features.subscription.toSubscriptionPaymentResponse
import kotlinx.datetime.*
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** 갱신 청구 결과 */
enum class RenewalResult { SUCCESS, DECLINED, UNKNOWN, SKIPPED }  // SKIPPED는 청구 직전 해지

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
    ) {
        val ctx = query {
            val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                ?: throw SubscriptionNotFoundException(subscriptionId)

            if (subscription.userId != userId) {
                throw ForbiddenException(Errors.Common.PERMISSION_DENIED)
            }

            val plan = planRepository.findPlanById(subscription.planId)
                ?: throw SubscriptionPlanNotFoundException(subscription.planId)

            val billing = billingKeyRepository.findActiveByUserId(userId)
                ?: throw BillingKeyRequiredException(Errors.Commerce.Payment.BILLING_KEY_REQUIRED)

            ChargeContext(plan.price, plan.name, billing.billingKey, billing.customerKey)
        }

        val orderId = generateNumericOrderId()
        // 기록 실패도 정리 작업이 확정하도록 청구 전 대기 기록
        val pendingPaymentId = query {
            paymentRepository.createSubPayment(
                subscriptionId = subscriptionId,
                provider = PaymentProvider.TOSS_BILLING,
                amount = ctx.amount,
                isRenewal = false,
                transactionId = orderId
            ).id.value
        }

        val charged = try {
            chargeOrUnknown(ctx, orderId, "구독 - ${ctx.planName}")
        } catch (e: Exception) {
            // 구독 롤백이 막히지 않게 대기 기록 삭제
            try {
                query { paymentRepository.deletePayment(pendingPaymentId) }
            } catch (cleanupError: Exception) {
                logger.error("구독 초회 결제 대기 기록 삭제 실패, 정리 작업이 확정: paymentId={}, error={}", pendingPaymentId, cleanupError.message)
            }
            throw e
        }
        val confirmed = charged ?: run {
            // 청구 불명은 대기 기록·구독을 두고 정리 작업이 확정
            logger.warn("구독 초회 청구 결과 불명, 정리 작업이 확정: subscriptionId={}, orderId={}", subscriptionId, orderId)
            throw PaymentResultUnknownException(Errors.Commerce.Payment.SUBSCRIPTION_PAYMENT_RESULT_UNKNOWN)
        }

        try {
            query {
                recordSubPayment(subscriptionId, ctx.amount, isRenewal = false, orderId = orderId, confirmed = confirmed)
            }
        } catch (e: Exception) {
            logger.error("구독 초회 결제 기록 실패(청구는 완료됨, 정리 작업이 확정): subscriptionId={}, orderId={}, error={}", subscriptionId, orderId, e.message)
        }
    }

    suspend fun processRenewalPayment(subscriptionId: Int): RenewalResult {
        var cancelled = false
        // DECLINED만 실패, UNKNOWN은 재시도
        val captured = try {
            query {
                val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                    ?: return@query null
                // 대상 조회 뒤 해지됐으면 청구하지 않음
                if (!subscription.autoRenew || subscription.status != SubscriptionStatus.ACTIVE) {
                    cancelled = true
                    return@query null
                }
                val plan = planRepository.findPlanById(subscription.planId)
                    ?: return@query null
                val billing = billingKeyRepository.findActiveByUserId(subscription.userId)
                    ?: return@query null
                ChargeContext(plan.price, plan.name, billing.billingKey, billing.customerKey) to
                    subscription.expiresAt
            }
        } catch (e: Exception) {
            logger.error("갱신 결제 준비 실패(다음 주기 재시도): subscriptionId={}, error={}", subscriptionId, e.message)
            return RenewalResult.UNKNOWN
        } ?: return if (cancelled) RenewalResult.SKIPPED else RenewalResult.DECLINED  // 재시도 무의미

        val (ctx, expiresAt) = captured
        // 멱등키
        val orderId = "$subscriptionId${expiresAt.date.format(LocalDate.Formats.ISO_BASIC)}"
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
            // 기록과 만료일 연장은 한 트랜잭션
            query {
                recordSubPayment(subscriptionId, ctx.amount, isRenewal = true, orderId = orderId, confirmed = confirmed)
                subscriptionRepository.extendSubscription(
                    subscriptionId = subscriptionId,
                    newExpiresAt = expiresAt.date
                        .plus(DatePeriod(days = Constants.Subscription.SUBSCRIPTION_DURATION_DAYS))
                        .atTime(expiresAt.hour, expiresAt.minute, expiresAt.second, expiresAt.nanosecond)
                )
            }
            RenewalResult.SUCCESS
        } catch (e: Exception) {
            // 재시도는 멱등키라 재과금 없음
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
    ) {
        val ctx = query {
            val billing = billingKeyRepository.findActiveByUserId(userId)
                ?: throw BillingKeyRequiredException(Errors.Commerce.Payment.BILLING_KEY_REQUIRED)
            ChargeContext(diffAmount, planName, billing.billingKey, billing.customerKey)
        }

        // 재시도가 같은 키를 쓰도록 결정적 멱등키
        val orderId = upgradeOrderId(subscriptionId, newPlanId)
        val confirmed = charge(ctx, orderId, "구독 업그레이드 - ${ctx.planName}")

        // 기록과 플랜 변경은 한 트랜잭션
        query {
            recordSubPayment(subscriptionId, diffAmount, isRenewal = false, orderId = orderId, confirmed = confirmed)
            subscriptionRepository.updateSubscriptionPlan(subscriptionId, newPlanId)
        }
    }

    /** 방치된 구독 결제 대기 기록 확정 */
    suspend fun reconcileStalePendingPayments(olderThanMinutes: Long): Int {
        val before = (Clock.System.now() - olderThanMinutes.minutes).toLocalDateTime(TimeZone.UTC)

        val stale = query {
            paymentRepository.findStalePendingSubPayments(before).mapNotNull { payment ->
                val subscriptionId = payment.subscriptionId ?: return@mapNotNull null
                val orderId = payment.transactionId ?: return@mapNotNull null
                Triple(payment.id.value, subscriptionId, orderId)
            }
        }

        var processed = 0
        for ((paymentId, subscriptionId, orderId) in stale) {
            try {
                val paidToss = tossPaymentClient.findPaymentByOrderId(orderId)?.takeIf { it.status == "DONE" }
                query {
                    val payment = paymentRepository.findById(paymentId) ?: return@query
                    if (payment.status != PaymentStatus.PENDING) return@query  // 멱등 처리

                    if (paidToss != null) {
                        // 금액 불일치 시 수동 개입
                        if (payment.amount.compareTo(BigDecimal(paidToss.totalAmount)) != 0) {
                            logger.error(
                                "[정리] 구독 결제금액 불일치로 완료 보류: paymentId={}, server={}, toss={}",
                                paymentId, payment.amount, paidToss.totalAmount
                            )
                            return@query
                        }
                        completeSubPayment(paymentId, paidToss)
                    } else {
                        // 미청구 확정, 결제 없이 열린 구독은 만료
                        paymentRepository.updateStatus(paymentId, PaymentStatus.FAILED)
                        val hasPaid = paymentRepository.findAllBySubscriptionId(subscriptionId)
                            .any { it.status == PaymentStatus.COMPLETED }
                        if (!hasPaid) subscriptionRepository.expireSubscription(subscriptionId)
                    }
                }
                processed++
            } catch (e: Exception) {
                logger.error("[정리] 구독 결제 대기 기록 보정 실패: paymentId={}, error={}", paymentId, e.message)
            }
        }
        return processed
    }

    suspend fun getPaymentHistory(
        subscriptionId: Int,
        userId: Int
    ): List<SubscriptionPaymentResponse> {
        return query {
            val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                ?: throw SubscriptionNotFoundException(subscriptionId)

            if (subscription.userId != userId) {
                throw ForbiddenException(Errors.Common.PERMISSION_DENIED)
            }

            paymentRepository.findAllBySubscriptionId(subscriptionId).map { it.toSubscriptionPaymentResponse() }
        }
    }

    private fun upgradeOrderId(subscriptionId: Int, newPlanId: Int): String {
        val now = nowUtc()
        val dateStr = now.date.format(LocalDate.Formats.ISO_BASIC)
        return "$dateStr${subscriptionId.toString().padStart(6, '0')}${newPlanId.toString().padStart(4, '0')}"
    }

    private fun generateNumericOrderId(): String {
        val now = nowUtc()
        val dateStr = now.date.format(LocalDate.Formats.ISO_BASIC)
        val randomStr = (10_000_000..99_999_999).random()  // 날짜8 + 랜덤8 숫자
        return "$dateStr$randomStr"
    }

    /** 토스 빌링 청구 */
    private suspend fun charge(ctx: ChargeContext, orderId: String, orderName: String): TossPaymentResponse =
        chargeOrUnknown(ctx, orderId, orderName)
            ?: throw PaymentConfirmFailedException(Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)

    /** 결과 불명 허용 토스 빌링 청구 */
    private suspend fun chargeOrUnknown(ctx: ChargeContext, orderId: String, orderName: String): TossPaymentResponse? {
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
                // 재시도 이중 청구 방지용 토스 조회
                val lookup = runCatching { tossPaymentClient.findPaymentByOrderId(orderId) }
                    .onFailure { logger.error("구독 청구 결과 확인 실패: orderId={}, error={}", orderId, it.message) }
                lookup.getOrNull()?.takeIf { it.status == "DONE" } ?: run {
                    // 토스 무응답·5xx·조회 실패는 청구 여부 불명
                    if (result.httpStatus == 0 || result.httpStatus >= 500 || lookup.isFailure) return null
                    throw PaymentConfirmFailedException(result.message ?: Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
                }
            }
            is TossConfirmResult.Success -> result.payment
        }
        if (confirmed.status != "DONE") {
            throw PaymentConfirmFailedException(Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)
        }
        return confirmed
    }

    private suspend fun recordSubPayment(
        subscriptionId: Int,
        amount: BigDecimal,
        isRenewal: Boolean,
        orderId: String,
        confirmed: TossPaymentResponse
    ) {
        // 청구 전 대기 기록을 완료로 전환
        val existing = paymentRepository.findByTransactionId(orderId)
        if (existing?.status == PaymentStatus.COMPLETED) return  // 같은 멱등키로 이미 완료

        val paymentId = existing?.id?.value ?: paymentRepository.createSubPayment(
            subscriptionId = subscriptionId,
            provider = PaymentProvider.TOSS_BILLING,
            amount = amount,
            isRenewal = isRenewal,
            transactionId = orderId
        ).id.value
        completeSubPayment(paymentId, confirmed)
    }

    private suspend fun completeSubPayment(paymentId: Int, confirmed: TossPaymentResponse) {
        paymentRepository.updatePaymentInfo(
            paymentId = paymentId,
            paymentKey = confirmed.paymentKey,
            receiptUrl = confirmed.receipt?.url
        )
        paymentRepository.updateStatus(paymentId, PaymentStatus.COMPLETED)
    }
}
