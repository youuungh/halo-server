package com.ninezero.service

import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.SubscriptionStatus
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.subscription.domain.RenewalResult
import com.ninezero.features.subscription.domain.SubscriptionPaymentService
import com.ninezero.features.subscription.domain.SubscriptionService
import com.ninezero.features.subscription.presentation.models.request.SubscribeRequest
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.minusDays
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionServiceTest {

    private lateinit var subscriptionService: SubscriptionService
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var planRepository: SubscriptionPlanRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var notificationService: NotificationService
    private lateinit var subscriptionPaymentService: SubscriptionPaymentService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            subscriptionRepository = SubscriptionRepositoryImpl()
            planRepository = SubscriptionPlanRepositoryImpl()
            userRepository = UserRepositoryImpl()

            // Mock 객체 생성
            notificationService = mockk(relaxed = true)
            subscriptionPaymentService = mockk(relaxed = true)

            // Mock 동작 정의

            subscriptionService = SubscriptionService(
                subscriptionRepository = subscriptionRepository,
                planRepository = planRepository,
                userRepository = userRepository,
                followRepository = FollowRepositoryImpl(),
                notificationService = notificationService,
                subscriptionPaymentService = subscriptionPaymentService,
                cacheService = mockk(relaxed = true),
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()
            // 결제 실패 테스트가 바꾼 스텁을 기본 성공으로 되돌림
            coEvery { subscriptionPaymentService.processInitialPayment(any(), any()) } returns Unit
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    /** 테스트용 플랜 */
    private suspend fun seedPlan(
        creatorId: Int,
        tier: SubscriptionPlanTier = SubscriptionPlanTier.TIER1,
        price: BigDecimal = BigDecimal("5000")
    ) = query {
        planRepository.createPlan(
            creatorId = creatorId,
            name = "기본 플랜",
            tier = tier,
            description = "기본 구독 플랜",
            price = price,
            benefits = "[\"혜택1\"]"
        )
    }

    // ===== 동시성 테스트 =====

    @Test
    fun `동시 구독 신청 - 중복 구독·이중 청구 방지`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)
            val request = SubscribeRequest(planId = plan.id.value, autoRenew = true)

            val results = listOf(
                async(Dispatchers.IO) { runCatching { subscriptionService.subscribe(user.id.value, request) } },
                async(Dispatchers.IO) { runCatching { subscriptionService.subscribe(user.id.value, request) } }
            ).awaitAll()

            val activeCount = query {
                subscriptionRepository.countUserSubscriptions(user.id.value, SubscriptionStatus.ACTIVE)
            }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, activeCount)
        }
    }

    // ===== 구독 생성 테스트 =====

    @Test
    fun `구독 결제 결과를 모르면 구독을 지우지 않고 정리 작업에 맡긴다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)
            coEvery { subscriptionPaymentService.processInitialPayment(any(), any()) } throws PaymentResultUnknownException()

            assertFailsWith<PaymentResultUnknownException> {
                subscriptionService.subscribe(user.id.value, SubscribeRequest(planId = plan.id.value, autoRenew = true))
            }
            val active = query { subscriptionRepository.countUserSubscriptions(user.id.value, SubscriptionStatus.ACTIVE) }
            assertEquals(1, active)
        }
    }

    @Test
    fun `구독 결제가 실패로 확정되면 만든 구독을 지운다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)
            coEvery { subscriptionPaymentService.processInitialPayment(any(), any()) } throws
                PaymentConfirmFailedException("카드 한도 초과")

            assertFailsWith<PaymentConfirmFailedException> {
                subscriptionService.subscribe(user.id.value, SubscribeRequest(planId = plan.id.value, autoRenew = true))
            }
            val active = query { subscriptionRepository.countUserSubscriptions(user.id.value, SubscriptionStatus.ACTIVE) }
            assertEquals(0, active)
        }
    }

    @Test
    fun `새 구독 알림은 크리에이터에게 구독자 프로필 딥링크로 간다`() {
        runBlocking {
            clearMocks(notificationService, answers = false)
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)

            subscriptionService.subscribe(user.id.value, SubscribeRequest(planId = plan.id.value, autoRenew = true))

            coVerify(timeout = 2000) {
                notificationService.sendNewSubscriberNotification(
                    creatorId = creator.id.value,
                    subscriberId = user.id.value,
                    subscriberUsername = any(),
                    planName = any()
                )
            }
        }
    }

    @Test
    fun `구독 생성 실패 - 본인 구독 시도`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)

            val request = SubscribeRequest(
                planId = plan.id.value,
                autoRenew = true
            )

            assertFailsWith<ValidationException> {
                subscriptionService.subscribe(creator.id.value, request)
            }
        }
    }

    @Test
    fun `구독 생성 실패 - 중복 구독`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)

            val request = SubscribeRequest(
                planId = plan.id.value,
                autoRenew = true
            )

            // 첫 번째 구독 성공
            subscriptionService.subscribe(user.id.value, request)

            // 두 번째 구독 시도 - 중복 구독
            assertFailsWith<SubscriptionAlreadyActiveException> {
                subscriptionService.subscribe(user.id.value, request)
            }
        }
    }

    @Test
    fun `구독 생성 실패 - 존재하지 않는 플랜`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val request = SubscribeRequest(
                planId = 99999,
                autoRenew = true
            )

            assertFailsWith<SubscriptionPlanNotFoundException> {
                subscriptionService.subscribe(user.id.value, request)
            }
        }
    }

    @Test
    fun `구독 생성 실패 - 비활성화된 플랜`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)

            // 플랜 비활성화
            query {
                planRepository.updatePlan(
                    planId = plan.id.value,
                    name = null,
                    description = null,
                    price = null,
                    benefits = null,
                    isActive = false
                )
            }

            val request = SubscribeRequest(
                planId = plan.id.value,
                autoRenew = true
            )

            assertFailsWith<ValidationException> {
                subscriptionService.subscribe(user.id.value, request)
            }
        }
    }

    // ===== 구독 조회 테스트 =====

    @Test
    fun `내 구독 목록 조회 - activeCount는 페이지와 무관한 전체 활성 구독 수`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            listOf("creator1", "creator2").forEach { name ->
                val creator = TestFixtures.createTestCreator(email = "$name@example.com", username = name)
                val plan = seedPlan(creator.id.value)
                subscriptionService.subscribe(user.id.value, SubscribeRequest(plan.id.value, true))
            }

            val response = subscriptionService.getMySubscriptions(user.id.value, null, 1, 1)

            assertEquals(1, response.subscriptions.items.size)
            assertEquals(2, response.subscriptions.totalCount)
            assertEquals(2, response.activeCount)
        }
    }

    // ===== 구독 취소 테스트 =====

    @Test
    fun `구독 취소 성공 - 자동갱신 비활성화`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)

            val subscription = subscriptionService.subscribe(
                user.id.value,
                SubscribeRequest(plan.id.value, true)
            )

            val message = subscriptionService.cancelSubscription(user.id.value, subscription.id)

            assertEquals(Messages.Subscription.SUBSCRIPTION_CANCELLED, message)

            // 구독 상태 확인 (자동갱신만 비활성화됨)
            val (status, autoRenew) = query {
                subscriptionRepository.findSubscriptionById(subscription.id)!!.let { it.status to it.autoRenew }
            }
            assertEquals(SubscriptionStatus.ACTIVE, status)  // 여전히 ACTIVE
            assertEquals(false, autoRenew)  // 자동갱신만 비활성화
        }
    }

    @Test
    fun `구독 취소 실패 - 권한 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)

            val subscription = subscriptionService.subscribe(
                user1.id.value,
                SubscribeRequest(plan.id.value, true)
            )

            // user2가 user1의 구독 취소 시도
            assertFailsWith<ForbiddenException> {
                subscriptionService.cancelSubscription(user2.id.value, subscription.id)
            }
        }
    }

    @Test
    fun `구독 취소 실패 - 이미 자동갱신 비활성화됨`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)

            val subscription = subscriptionService.subscribe(
                user.id.value,
                SubscribeRequest(plan.id.value, false)  // 자동갱신 false
            )

            // 이미 자동갱신이 비활성화된 상태에서 취소 시도
            assertFailsWith<ValidationException> {
                subscriptionService.cancelSubscription(user.id.value, subscription.id)
            }
        }
    }

    // ===== 자동 갱신 테스트 =====

    @Test
    fun `자동 갱신 활성화 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)

            val subscription = subscriptionService.subscribe(
                user.id.value,
                SubscribeRequest(plan.id.value, false)
            )

            // 자동갱신 활성화
            subscriptionService.updateAutoRenew(user.id.value, subscription.id, true)

            val autoRenew = query { subscriptionRepository.findSubscriptionById(subscription.id)!!.autoRenew }

            assertEquals(true, autoRenew)
        }
    }

    // ===== 구독 상태 확인 테스트 =====

    @Test
    fun `구독 상태 확인 - 구독 중`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)

            subscriptionService.subscribe(
                user.id.value,
                SubscribeRequest(plan.id.value, true)
            )

            val status = subscriptionService.checkSubscriptionStatus(user.id.value, creator.id.value)

            assertTrue(status.isSubscribed)
            assertNotNull(status.subscriptionId)
            assertNotNull(status.planId)
            assertNotNull(status.expiresAt)
        }
    }

    // ===== 만료·자동 갱신 =====

    @Test
    fun `기한 지난 구독은 만료되고 구독자에게 알림이 간다`() {
        runBlocking {
            clearMocks(notificationService, answers = false)
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)
            val subscriptionId = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value, creatorId = creator.id.value, planId = plan.id.value,
                    startedAt = nowUtc().minusDays(31), expiresAt = nowUtc().minusDays(1), autoRenew = false
                ).id.value
            }

            val result = subscriptionService.expireSubscriptions()

            assertEquals(1, result.successCount)
            assertEquals(SubscriptionStatus.EXPIRED, query { subscriptionRepository.findSubscriptionById(subscriptionId)!!.status })
            coVerify(exactly = 1) {
                notificationService.sendSubscriptionExpiredNotification(user.id.value, subscriptionId, any(), any())
            }
        }
    }

    @Test
    fun `자동 갱신 청구가 거절되면 자동 갱신을 끄고 구독자에게 알린다`() {
        runBlocking {
            clearMocks(notificationService, answers = false)
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val plan = seedPlan(creator.id.value)
            val subscriptionId = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value, creatorId = creator.id.value, planId = plan.id.value,
                    startedAt = nowUtc().minusDays(30), expiresAt = (Clock.System.now() + 12.hours).toLocalDateTime(TimeZone.UTC),
                    autoRenew = true
                ).id.value
            }
            coEvery { subscriptionPaymentService.processRenewalPayment(subscriptionId) } returns RenewalResult.DECLINED

            val result = subscriptionService.renewSubscriptions()

            assertEquals(1, result.failCount)
            assertEquals(false, query { subscriptionRepository.findSubscriptionById(subscriptionId)!!.autoRenew })
            coVerify(exactly = 1) {
                notificationService.sendSubscriptionRenewalFailedNotification(user.id.value, subscriptionId, any(), any(), any())
            }
        }
    }
}
