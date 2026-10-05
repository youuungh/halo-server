package com.ninezero.service

import com.ninezero.core.common.config.PaymentProvider
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.SubscriptionStatus
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.PaymentConfirmFailedException
import com.ninezero.core.common.exception.PaymentResultUnknownException
import com.ninezero.core.common.exception.SubscriptionNotFoundException
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.payment.TossConfirmResult
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.core.payment.TossPaymentResponse
import com.ninezero.features.commerce.data.BillingKeyRepositoryImpl
import com.ninezero.features.commerce.data.PaymentRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.subscription.domain.RenewalResult
import com.ninezero.features.subscription.domain.SubscriptionPaymentService
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionPaymentServiceTest {

    private lateinit var subscriptionPaymentService: SubscriptionPaymentService
    private lateinit var paymentRepository: PaymentRepositoryImpl
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var planRepository: SubscriptionPlanRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var billingKeyRepository: BillingKeyRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            paymentRepository = PaymentRepositoryImpl()
            subscriptionRepository = SubscriptionRepositoryImpl()
            planRepository = SubscriptionPlanRepositoryImpl()
            userRepository = UserRepositoryImpl()
            billingKeyRepository = BillingKeyRepositoryImpl()

            // 빌링 청구는 토스 네트워크 호출이라 목으로 성공 응답을 고정한다(빌링키는 각 테스트에서 시드).
            val tossPaymentClient = mockk<TossPaymentClient>()
            coEvery {
                tossPaymentClient.chargeBilling(any(), any(), any(), any(), any(), any())
            } returns TossConfirmResult.Success(
                TossPaymentResponse(paymentKey = "test_pk", orderId = "test_oid", status = "DONE", totalAmount = 5000L)
            )

            subscriptionPaymentService = serviceWith(tossPaymentClient)
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    /** 구독 결제 테스트용 빌링키 시드(유저당 활성 1개). */
    private suspend fun seedBillingKey(userId: Int) {
        query {
            billingKeyRepository.create(
                userId = userId,
                customerKey = "ck_$userId",
                billingKey = "bk_$userId",
                cardCompany = "신한",
                cardNumberMasked = "1234-****-****-1234",
                cardType = "신용",
                ownerType = "개인",
                authenticatedAt = null
            )
        }
    }

    /** 불명/거절 판정 테스트용 — 별도 토스 목을 주입한 서비스 인스턴스. */
    private fun serviceWith(tossPaymentClient: TossPaymentClient) = SubscriptionPaymentService(
        paymentRepository = paymentRepository,
        subscriptionRepository = subscriptionRepository,
        planRepository = planRepository,
        billingKeyRepository = billingKeyRepository,
        tossPaymentClient = tossPaymentClient
    )

    /** 테스트용 구독 시드(크리에이터·플랜 포함). subscriptionId 반환. */
    private suspend fun seedSubscription(userId: Int, price: BigDecimal = BigDecimal("5000")): Int {
        val creator = TestFixtures.createTestCreator()
        return query {
            val plan = planRepository.createPlan(
                creatorId = creator.id.value,
                name = "기본 플랜",
                tier = SubscriptionPlanTier.TIER1,
                description = "기본 구독 플랜",
                price = price,
                benefits = "[\"혜택1\"]"
            )
            subscriptionRepository.createSubscription(
                userId = userId,
                creatorId = creator.id.value,
                planId = plan.id.value,
                startedAt = nowUtc(),
                expiresAt = nowUtc().let {
                    it.date.plus(DatePeriod(days = 30))
                        .atTime(it.hour, it.minute, it.second, it.nanosecond)
                },
                autoRenew = true
            ).id.value
        }
    }

    // ===== 구독 결제 테스트 =====

    @Test
    fun `구독 결제 실패 - 권한 없음 (다른 사용자)`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val subscriptionId = seedSubscription(user1.id.value)

            assertFailsWith<ForbiddenException> {
                subscriptionPaymentService.processInitialPayment(
                    subscriptionId,
                    user2.id.value
                )
            }
        }
    }

    @Test
    fun `구독 결제 실패 - 존재하지 않는 구독`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)

            assertFailsWith<SubscriptionNotFoundException> {
                subscriptionPaymentService.processInitialPayment(99999, user.id.value)
            }
        }
    }

    @Test
    fun `구독 결제 금액 확인`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value, price = BigDecimal("10000"))

            subscriptionPaymentService.processInitialPayment(
                subscriptionId,
                user.id.value
            )

            val payments = query {
                paymentRepository.findAllBySubscriptionId(subscriptionId)
            }
            assertEquals(1, payments.size)
            assertEquals(0, BigDecimal("10000").compareTo(payments.first().amount))
        }
    }

    @Test
    fun `구독 결제 - 결과 불명이어도 토스 조회로 청구가 확인되면 성공 처리`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            // 청구 응답은 유실(타임아웃)됐지만 토스에는 실제 청구된 상황
            val flakyClient = mockk<TossPaymentClient>()
            coEvery { flakyClient.chargeBilling(any(), any(), any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "TOSS_REQUEST_FAILED", message = "timeout", httpStatus = 0)
            coEvery { flakyClient.findPaymentByOrderId(any()) } returns
                TossPaymentResponse(paymentKey = "test_pk", orderId = "resolved_oid", status = "DONE", totalAmount = 5000L)

            serviceWith(flakyClient).processInitialPayment(subscriptionId, user.id.value)

            val payments = query { paymentRepository.findAllBySubscriptionId(subscriptionId) }
            assertEquals(1, payments.size)
            assertEquals(PaymentStatus.COMPLETED, payments.first().status)
        }
    }

    @Test
    fun `구독 결제 - 토스 무응답이면 결과 불명으로 대기 기록을 남겨 정리 작업에 맡긴다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            val downClient = mockk<TossPaymentClient>()
            coEvery { downClient.chargeBilling(any(), any(), any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "TOSS_REQUEST_FAILED", message = "timeout", httpStatus = 0)
            coEvery { downClient.findPaymentByOrderId(any()) } returns null

            assertFailsWith<PaymentResultUnknownException> {
                serviceWith(downClient).processInitialPayment(subscriptionId, user.id.value)
            }
            val payments = query { paymentRepository.findAllBySubscriptionId(subscriptionId) }
            assertEquals(listOf(PaymentStatus.PENDING), payments.map { it.status })
        }
    }

    @Test
    fun `구독 결제 - 카드 거절이 아닌 4xx에 토스 기록도 없으면 미청구 확정(기록 없음)`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            val rejectClient = mockk<TossPaymentClient>()
            coEvery { rejectClient.chargeBilling(any(), any(), any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "PROVIDER_ERROR", message = "일시적인 오류", httpStatus = 400)
            coEvery { rejectClient.findPaymentByOrderId(any()) } returns null

            assertFailsWith<PaymentConfirmFailedException> {
                serviceWith(rejectClient).processInitialPayment(subscriptionId, user.id.value)
            }
            val payments = query { paymentRepository.findAllBySubscriptionId(subscriptionId) }
            assertEquals(0, payments.size)
        }
    }

    @Test
    fun `구독 결제 - 청구 후 기록이 실패해도 대기 기록이 남아 정리 작업이 완료 처리`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            val tossClient = mockk<TossPaymentClient>()
            coEvery { tossClient.chargeBilling(any(), any(), any(), any(), any(), any()) } returns
                TossConfirmResult.Success(
                    TossPaymentResponse(paymentKey = "test_pk", orderId = "test_oid", status = "DONE", totalAmount = 5000L)
                )
            // 청구는 성공, 완료 기록 트랜잭션만 실패
            val failingRepository = spyk(PaymentRepositoryImpl())
            coEvery { failingRepository.updatePaymentInfo(any(), any(), any()) } throws IllegalStateException("db down")
            val failingService = SubscriptionPaymentService(
                paymentRepository = failingRepository,
                subscriptionRepository = subscriptionRepository,
                planRepository = planRepository,
                billingKeyRepository = billingKeyRepository,
                tossPaymentClient = tossClient
            )

            failingService.processInitialPayment(subscriptionId, user.id.value)
            val pending = query { paymentRepository.findAllBySubscriptionId(subscriptionId) }
            assertEquals(listOf(PaymentStatus.PENDING), pending.map { it.status })

            val orderId = pending.first().transactionId!!
            coEvery { tossClient.findPaymentByOrderId(orderId) } returns
                TossPaymentResponse(paymentKey = "test_pk", orderId = orderId, status = "DONE", totalAmount = 5000L)
            val processed = serviceWith(tossClient).reconcileStalePendingPayments(olderThanMinutes = -1)

            assertEquals(1, processed)
            val settled = query { paymentRepository.findAllBySubscriptionId(subscriptionId).single() }
            assertEquals(PaymentStatus.COMPLETED, settled.status)
            assertEquals("test_pk", settled.paymentKey)
        }
    }

    @Test
    fun `구독 결제 정리 - 토스에 청구가 없으면 대기 기록 실패 처리하고 구독 만료`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val subscriptionId = seedSubscription(user.id.value)
            // 청구 전에 프로세스가 죽어 대기 기록만 남은 상황
            query {
                paymentRepository.createSubPayment(
                    subscriptionId = subscriptionId,
                    provider = PaymentProvider.TOSS_BILLING,
                    amount = BigDecimal("5000"),
                    transactionId = "2026091112345678"
                )
            }
            val tossClient = mockk<TossPaymentClient>()
            coEvery { tossClient.findPaymentByOrderId(any()) } returns null

            serviceWith(tossClient).reconcileStalePendingPayments(olderThanMinutes = -1)

            val paymentStatus = query { paymentRepository.findAllBySubscriptionId(subscriptionId).single().status }
            val subscriptionStatus = query { subscriptionRepository.findSubscriptionById(subscriptionId)!!.status }
            assertEquals(PaymentStatus.FAILED, paymentStatus)
            assertEquals(SubscriptionStatus.EXPIRED, subscriptionStatus)
        }
    }

    // ===== 자동 갱신 결제 테스트 =====

    @Test
    fun `자동 갱신 결제 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            val result = subscriptionPaymentService.processRenewalPayment(subscriptionId)

            assertEquals(RenewalResult.SUCCESS, result)

            val payments = query {
                paymentRepository.findAllBySubscriptionId(subscriptionId)
            }
            assertEquals(1, payments.size)
            assertEquals(true, payments.first().isRenewal)

            // 다음 회차 갱신도 따로 청구·기록(회차마다 멱등키가 다름)
            assertEquals(RenewalResult.SUCCESS, subscriptionPaymentService.processRenewalPayment(subscriptionId))
            assertEquals(2, query { paymentRepository.findAllBySubscriptionId(subscriptionId) }.count { it.isRenewal })
        }
    }

    @Test
    fun `자동 갱신 결제 - 존재하지 않는 구독은 DECLINED 반환`() {
        runBlocking {
            val result = subscriptionPaymentService.processRenewalPayment(99999)

            assertEquals(RenewalResult.DECLINED, result)
        }
    }

    @Test
    fun `자동 갱신 결제 - 대상 조회 뒤 해지된 구독은 청구하지 않고 SKIPPED`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)
            query { subscriptionRepository.updateAutoRenew(subscriptionId, false) }

            val tossClient = mockk<TossPaymentClient>()
            val result = serviceWith(tossClient).processRenewalPayment(subscriptionId)

            assertEquals(RenewalResult.SKIPPED, result)
            coVerify(exactly = 0) { tossClient.chargeBilling(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `자동 갱신 결제 - 결과 불명이면 UNKNOWN 반환하고 기록하지 않음`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            val timeoutClient = mockk<TossPaymentClient>()
            coEvery { timeoutClient.chargeBilling(any(), any(), any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "TOSS_REQUEST_FAILED", message = "timeout", httpStatus = 0)

            val result = serviceWith(timeoutClient).processRenewalPayment(subscriptionId)

            assertEquals(RenewalResult.UNKNOWN, result)
            val payments = query { paymentRepository.findAllBySubscriptionId(subscriptionId) }
            assertEquals(0, payments.size)
        }
    }

    @Test
    fun `자동 갱신 결제 - 확정 거절이면 DECLINED 반환`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            val declineClient = mockk<TossPaymentClient>()
            coEvery { declineClient.chargeBilling(any(), any(), any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "REJECT_CARD_PAYMENT", message = "카드 한도 초과", httpStatus = 400)

            val result = serviceWith(declineClient).processRenewalPayment(subscriptionId)

            assertEquals(RenewalResult.DECLINED, result)
        }
    }

    @Test
    fun `자동 갱신 결제 - 시크릿 키 오류는 거절이 아니라 UNKNOWN`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            // 키 설정 실수로 그 시간대 전원이 401을 받아도 자동갱신이 꺼지면 안 됨
            val badKeyClient = mockk<TossPaymentClient>()
            coEvery { badKeyClient.chargeBilling(any(), any(), any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "UNAUTHORIZED_KEY", message = "인증되지 않은 시크릿 키", httpStatus = 401)

            val result = serviceWith(badKeyClient).processRenewalPayment(subscriptionId)

            assertEquals(RenewalResult.UNKNOWN, result)
        }
    }

    @Test
    fun `자동 갱신 결제 - 카드 거절 코드가 아닌 4xx는 UNKNOWN`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            val providerErrorClient = mockk<TossPaymentClient>()
            coEvery { providerErrorClient.chargeBilling(any(), any(), any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "PROVIDER_ERROR", message = "일시적인 오류", httpStatus = 400)

            val result = serviceWith(providerErrorClient).processRenewalPayment(subscriptionId)

            assertEquals(RenewalResult.UNKNOWN, result)
        }
    }

    // ===== 결제 내역 조회 테스트 =====

    @Test
    fun `결제 내역 조회 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            subscriptionPaymentService.processInitialPayment(subscriptionId, user.id.value)

            val history = subscriptionPaymentService.getPaymentHistory(
                subscriptionId,
                user.id.value
            )

            assertEquals(1, history.size)
            assertEquals(0, BigDecimal("5000").compareTo(BigDecimal(history.first().amount)))
            assertEquals(PaymentStatus.COMPLETED, history.first().status)

            // 응답 그대로 JSON이 되고 결제 키는 내보내지 않음
            val json = Json.encodeToString(history)
            assertFalse(json.contains("paymentKey"))
        }
    }

    @Test
    fun `결제 내역 조회 실패 - 권한 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val subscriptionId = seedSubscription(user1.id.value)

            assertFailsWith<ForbiddenException> {
                subscriptionPaymentService.getPaymentHistory(
                    subscriptionId,
                    user2.id.value
                )
            }
        }
    }
}
