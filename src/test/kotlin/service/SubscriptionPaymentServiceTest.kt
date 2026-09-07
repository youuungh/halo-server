package com.ninezero.service

import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.PaymentConfirmFailedException
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
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * SubscriptionPaymentService 테스트
 *
 * 테스트 케이스: 16개
 * - 구독 결제: 6개
 * - 자동 갱신 결제: 6개
 * - 결제 내역 조회: 4개
 */
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

            subscriptionPaymentService = SubscriptionPaymentService(
                paymentRepository = paymentRepository,
                subscriptionRepository = subscriptionRepository,
                planRepository = planRepository,
                billingKeyRepository = billingKeyRepository,
                tossPaymentClient = tossPaymentClient
            )
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
    private suspend fun seedSubscription(userId: Int): Int {
        val creator = TestFixtures.createTestCreator()
        return query {
            val plan = planRepository.createPlan(
                creatorId = creator.id.value,
                name = "기본 플랜",
                tier = SubscriptionPlanTier.TIER1,
                description = "기본 구독 플랜",
                price = BigDecimal("5000"),
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

    // ===== 구독 결제 테스트 (6개) =====

    @Test
    fun `구독 결제 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val creator = TestFixtures.createTestCreator()
            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택1\"]"
                )
            }

            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().let {
                        it.date.plus(DatePeriod(days = 30))
                            .atTime(it.hour, it.minute, it.second, it.nanosecond)
                    },
                    autoRenew = true
                )
            }

            val message = subscriptionPaymentService.processInitialPayment(
                subscription.id.value,
                user.id.value
            )

            assertNotNull(message)

            val payments = query {
                paymentRepository.findAllBySubscriptionId(subscription.id.value)
            }
            assertEquals(1, payments.size)
            assertEquals(PaymentStatus.COMPLETED, payments.first().status)
        }
    }

    @Test
    fun `구독 결제 실패 - 권한 없음 (다른 사용자)`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val creator = TestFixtures.createTestCreator()
            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택1\"]"
                )
            }

            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user1.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().let {
                        it.date.plus(DatePeriod(days = 30))
                            .atTime(it.hour, it.minute, it.second, it.nanosecond)
                    },
                    autoRenew = true
                )
            }

            assertFailsWith<ForbiddenException> {
                subscriptionPaymentService.processInitialPayment(
                    subscription.id.value,
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
            val creator = TestFixtures.createTestCreator()
            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "프리미엄 플랜",
                    tier = SubscriptionPlanTier.TIER2,
                    description = "프리미엄 구독 플랜",
                    price = BigDecimal("10000"),
                    benefits = "[\"프리미엄 혜택\"]"
                )
            }

            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().let {
                        it.date.plus(DatePeriod(days = 30))
                            .atTime(it.hour, it.minute, it.second, it.nanosecond)
                    },
                    autoRenew = true
                )
            }

            subscriptionPaymentService.processInitialPayment(
                subscription.id.value,
                user.id.value
            )

            val payments = query {
                paymentRepository.findAllBySubscriptionId(subscription.id.value)
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

            val message = serviceWith(flakyClient).processInitialPayment(subscriptionId, user.id.value)

            assertNotNull(message)
            val payments = query { paymentRepository.findAllBySubscriptionId(subscriptionId) }
            assertEquals(1, payments.size)
            assertEquals(PaymentStatus.COMPLETED, payments.first().status)
        }
    }

    @Test
    fun `구독 결제 - 결과 불명 후 미청구로 확정되면 예외(기록 없음)`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val subscriptionId = seedSubscription(user.id.value)

            val downClient = mockk<TossPaymentClient>()
            coEvery { downClient.chargeBilling(any(), any(), any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "TOSS_REQUEST_FAILED", message = "timeout", httpStatus = 0)
            coEvery { downClient.findPaymentByOrderId(any()) } returns null

            assertFailsWith<PaymentConfirmFailedException> {
                serviceWith(downClient).processInitialPayment(subscriptionId, user.id.value)
            }
            val payments = query { paymentRepository.findAllBySubscriptionId(subscriptionId) }
            assertEquals(0, payments.size)
        }
    }

    // ===== 자동 갱신 결제 테스트 (6개) =====

    @Test
    fun `자동 갱신 결제 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val creator = TestFixtures.createTestCreator()
            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택1\"]"
                )
            }

            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().let {
                        it.date.plus(DatePeriod(days = 30))
                            .atTime(it.hour, it.minute, it.second, it.nanosecond)
                    },
                    autoRenew = true
                )
            }

            val result = subscriptionPaymentService.processRenewalPayment(subscription.id.value)

            assertEquals(RenewalResult.SUCCESS, result)

            val payments = query {
                paymentRepository.findAllBySubscriptionId(subscription.id.value)
            }
            assertEquals(1, payments.size)
            assertEquals(true, payments.first().isRenewal)
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
    fun `여러 번의 갱신 결제 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val creator = TestFixtures.createTestCreator()
            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택1\"]"
                )
            }

            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().let {
                        it.date.plus(DatePeriod(days = 30))
                            .atTime(it.hour, it.minute, it.second, it.nanosecond)
                    },
                    autoRenew = true
                )
            }

            repeat(3) {
                subscriptionPaymentService.processRenewalPayment(subscription.id.value)
            }

            val payments = query {
                paymentRepository.findAllBySubscriptionId(subscription.id.value)
            }
            assertEquals(3, payments.size)
            assertEquals(true, payments.all { it.isRenewal })
        }
    }

    @Test
    fun `초기 결제와 갱신 결제 구분`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val creator = TestFixtures.createTestCreator()
            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택1\"]"
                )
            }

            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().let {
                        it.date.plus(DatePeriod(days = 30))
                            .atTime(it.hour, it.minute, it.second, it.nanosecond)
                    },
                    autoRenew = true
                )
            }

            subscriptionPaymentService.processInitialPayment(subscription.id.value, user.id.value)
            subscriptionPaymentService.processRenewalPayment(subscription.id.value)

            val payments = query {
                paymentRepository.findAllBySubscriptionId(subscription.id.value)
            }
            assertEquals(2, payments.size)

            val initialPayment = payments.find { !it.isRenewal }
            val renewalPayment = payments.find { it.isRenewal }

            assertNotNull(initialPayment)
            assertNotNull(renewalPayment)
        }
    }

    // ===== 결제 내역 조회 테스트 (4개) =====

    @Test
    fun `결제 내역 조회 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val creator = TestFixtures.createTestCreator()
            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택1\"]"
                )
            }

            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().let {
                        it.date.plus(DatePeriod(days = 30))
                            .atTime(it.hour, it.minute, it.second, it.nanosecond)
                    },
                    autoRenew = true
                )
            }

            subscriptionPaymentService.processInitialPayment(subscription.id.value, user.id.value)

            val history = subscriptionPaymentService.getPaymentHistory(
                subscription.id.value,
                user.id.value
            )

            assertNotNull(history)
            assertEquals(1, history.size)
            assertEquals(0, BigDecimal("5000").compareTo(BigDecimal(history.first()["amount"] as String)))
            assertEquals("COMPLETED", history.first()["status"])
        }
    }

    @Test
    fun `결제 내역 조회 - 빈 목록`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val creator = TestFixtures.createTestCreator()
            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택1\"]"
                )
            }

            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().let {
                        it.date.plus(DatePeriod(days = 30))
                            .atTime(it.hour, it.minute, it.second, it.nanosecond)
                    },
                    autoRenew = true
                )
            }

            val history = subscriptionPaymentService.getPaymentHistory(
                subscription.id.value,
                user.id.value
            )

            assertNotNull(history)
            assertEquals(0, history.size)
        }
    }

    @Test
    fun `결제 내역 조회 실패 - 권한 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val creator = TestFixtures.createTestCreator()
            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택1\"]"
                )
            }

            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user1.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().let {
                        it.date.plus(DatePeriod(days = 30))
                            .atTime(it.hour, it.minute, it.second, it.nanosecond)
                    },
                    autoRenew = true
                )
            }

            assertFailsWith<ForbiddenException> {
                subscriptionPaymentService.getPaymentHistory(
                    subscription.id.value,
                    user2.id.value
                )
            }
        }
    }

    @Test
    fun `여러 결제 내역 조회`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            seedBillingKey(user.id.value)
            val creator = TestFixtures.createTestCreator()
            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택1\"]"
                )
            }

            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().let {
                        it.date.plus(DatePeriod(days = 30))
                            .atTime(it.hour, it.minute, it.second, it.nanosecond)
                    },
                    autoRenew = true
                )
            }

            subscriptionPaymentService.processInitialPayment(subscription.id.value, user.id.value)
            subscriptionPaymentService.processRenewalPayment(subscription.id.value)
            subscriptionPaymentService.processRenewalPayment(subscription.id.value)

            val history = subscriptionPaymentService.getPaymentHistory(
                subscription.id.value,
                user.id.value
            )

            assertNotNull(history)
            assertEquals(3, history.size)

            val initialPayments = history.filter { it["isRenewal"] == false }
            val renewalPayments = history.filter { it["isRenewal"] == true }

            assertEquals(1, initialPayments.size)
            assertEquals(2, renewalPayments.size)
        }
    }
}