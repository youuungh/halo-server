package com.ninezero.service

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.subscription.domain.SubscriptionPlanService
import com.ninezero.features.subscription.presentation.models.request.SubscriptionPlanRequest
import com.ninezero.features.subscription.presentation.models.request.UpdatePlanRequest
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.plusDays
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionPlanServiceTest {

    private lateinit var planService: SubscriptionPlanService
    private lateinit var planRepository: SubscriptionPlanRepositoryImpl
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var cacheService: CacheService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            planRepository = SubscriptionPlanRepositoryImpl()
            subscriptionRepository = SubscriptionRepositoryImpl()
            userRepository = UserRepositoryImpl()
            cacheService = mockk(relaxed = true)

            // CacheService mock 설정
            coEvery { cacheService.get<Any>(any(), any()) } returns null

            planService = SubscriptionPlanService(
                planRepository = planRepository,
                subscriptionRepository = subscriptionRepository,
                userRepository = userRepository,
                cacheService = cacheService
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

    // ===== 플랜 생성 테스트 =====

    @Test
    fun `플랜 생성 시 크리에이터 프로필 캐시를 지운다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest(
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜입니다",
                    price = "5000",
                    benefits = listOf("혜택1")
                )
            )

            coVerify { cacheService.delete(CacheKeys.profile(creator.id.value)) }
        }
    }

    @Test
    fun `플랜 생성 성공 - TIER1`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = SubscriptionPlanRequest(
                name = "기본 플랜",
                tier = SubscriptionPlanTier.TIER1,
                description = "기본 구독 플랜입니다",
                price = "5000",
                benefits = listOf("혜택1", "혜택2", "혜택3")
            )

            val response = planService.createPlan(creator.id.value, request)

            assertEquals("기본 플랜", response.name)
            assertEquals(SubscriptionPlanTier.TIER1, response.tier)
            assertEquals(0, BigDecimal(response.price).compareTo(BigDecimal("5000")))
            assertTrue(response.isActive)
        }
    }

    @Test
    fun `플랜 생성 실패 - 권한 없음 (일반 사용자)`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            val request = SubscriptionPlanRequest(
                name = "기본 플랜",
                tier = SubscriptionPlanTier.TIER1,
                description = "기본 구독 플랜입니다",
                price = "5000",
                benefits = listOf("혜택1")
            )

            assertFailsWith<CreatorOnlyException> {
                planService.createPlan(user.id.value, request)
            }
        }
    }

    @Test
    fun `플랜 생성 실패 - FREE 티어는 생성 불가`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = SubscriptionPlanRequest(
                name = "무료 플랜",
                tier = SubscriptionPlanTier.FREE,
                description = "무료 플랜입니다",
                price = "0",
                benefits = listOf("혜택1")
            )

            assertFailsWith<ValidationException> {
                planService.createPlan(creator.id.value, request)
            }
        }
    }

    @Test
    fun `플랜 생성 실패 - 같은 티어의 플랜이 이미 존재`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request1 = SubscriptionPlanRequest(
                name = "기본 플랜",
                tier = SubscriptionPlanTier.TIER1,
                description = "기본 구독 플랜입니다",
                price = "5000",
                benefits = listOf("혜택1")
            )

            planService.createPlan(creator.id.value, request1)

            // 같은 티어로 다시 생성 시도
            val request2 = SubscriptionPlanRequest(
                name = "또 다른 기본 플랜",
                tier = SubscriptionPlanTier.TIER1,
                description = "또 다른 기본 플랜",
                price = "6000",
                benefits = listOf("혜택2")
            )

            assertFailsWith<ConflictException> {
                planService.createPlan(creator.id.value, request2)
            }
        }
    }

    @Test
    fun `플랜 생성 실패 - 잘못된 가격 (음수·숫자 아님·상한 초과)`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = SubscriptionPlanRequest(
                name = "기본 플랜",
                tier = SubscriptionPlanTier.TIER1,
                description = "기본 구독 플랜입니다",
                price = "-1000",
                benefits = listOf("혜택1")
            )

            assertFailsWith<InvalidPriceException> {
                planService.createPlan(creator.id.value, request)
            }
            val formatError = assertFailsWith<InvalidPriceException> {
                planService.createPlan(creator.id.value, request.copy(price = "오천원"))
            }
            assertEquals(Errors.Commerce.Product.INVALID_PRICE_FORMAT, formatError.message)
            val rangeError = assertFailsWith<InvalidPriceException> {
                planService.createPlan(creator.id.value, request.copy(price = (Constants.Subscription.MAX_PLAN_PRICE + 1).toString()))
            }
            assertEquals(Errors.Subscription.PLAN_PRICE_RANGE_INVALID, rangeError.message)
        }
    }

    @Test
    fun `플랜 수정 실패 - 숫자가 아닌 가격과 상한 초과 가격`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val plan = planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("플랜", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택"))
            )
            val overMax = (Constants.Subscription.MAX_PLAN_PRICE + 1).toString()

            assertFailsWith<InvalidPriceException> {
                planService.updatePlan(plan.id, creator.id.value, UpdatePlanRequest(price = "오천원"))
            }
            assertFailsWith<InvalidPriceException> {
                planService.updatePlan(plan.id, creator.id.value, UpdatePlanRequest(price = overMax))
            }
        }
    }

    // ===== 플랜 조회 테스트 =====

    @Test
    fun `크리에이터의 플랜 목록 조회 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            // 2개 플랜 생성
            planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("플랜1", SubscriptionPlanTier.TIER1, "설명1", "5000", listOf("혜택1"))
            )
            planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("플랜2", SubscriptionPlanTier.TIER2, "설명2", "10000", listOf("혜택2"))
            )

            val response = planService.getCreatorPlans(creator.id.value, page = 1, limit = 10)

            assertEquals(2, response.items.size)
            assertEquals(2, response.totalCount)
        }
    }

    @Test
    fun `내가 만든 플랜 목록 조회 실패 - 권한 없음`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            assertFailsWith<CreatorOnlyException> {
                planService.getMyPlans(user.id.value, page = 1, limit = 10)
            }
        }
    }

    @Test
    fun `플랜 구독자 수는 만료·다른 플랜 이동을 반영한 현재 활성 구독 수`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val basic = planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("기본", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택"))
            )
            val premium = planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("프리미엄", SubscriptionPlanTier.TIER2, "설명", "10000", listOf("혜택"))
            )
            val subscribers = TestFixtures.createTestUsers(count = 3)
            val subscriptionIds = query {
                subscribers.map { user ->
                    subscriptionRepository.createSubscription(
                        userId = user.id.value,
                        creatorId = creator.id.value,
                        planId = basic.id,
                        startedAt = nowUtc(),
                        expiresAt = nowUtc().plusDays(30),
                        autoRenew = true
                    ).id.value
                }
            }
            query {
                subscriptionRepository.expireSubscription(subscriptionIds[0])  // 만료
                subscriptionRepository.updateSubscriptionPlan(subscriptionIds[1], premium.id)  // 상위 플랜으로 이동
            }

            val counts = planService.getMyPlans(creator.id.value, page = 1, limit = 10)
                .items.associate { it.id to it.subscriberCount }

            assertEquals(1, counts[basic.id])
            assertEquals(1, counts[premium.id])
            assertEquals(1, query { subscriptionRepository.countPlanSubscribers(basic.id) })
        }
    }

    // ===== 플랜 수정 테스트 =====

    @Test
    fun `플랜 수정 성공 - 가격 변경`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val created = planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("기본 플랜", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택1"))
            )

            val updateRequest = UpdatePlanRequest(
                price = "7000"
            )

            val updated = planService.updatePlan(created.id, creator.id.value, updateRequest)

            assertEquals(0, BigDecimal(updated.price).compareTo(BigDecimal("7000")))
        }
    }

    @Test
    fun `플랜 생성·수정 실패 - 상위 등급이 하위 등급보다 싸거나 같음`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val tier1 = planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("기본 플랜", SubscriptionPlanTier.TIER1, "설명", "9900", listOf("혜택1"))
            )

            assertFailsWith<InvalidPriceException> {
                planService.createPlan(
                    creator.id.value,
                    SubscriptionPlanRequest("프리미엄", SubscriptionPlanTier.TIER2, "설명", "4900", listOf("혜택2"))
                )
            }

            planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("프리미엄", SubscriptionPlanTier.TIER2, "설명", "19900", listOf("혜택2"))
            )
            assertFailsWith<InvalidPriceException> {
                planService.updatePlan(tier1.id, creator.id.value, UpdatePlanRequest(price = "19900"))
            }
        }
    }

    @Test
    fun `플랜 수정 실패 - 권한 없음 (다른 크리에이터)`() {
        runBlocking {
            val creator1 = TestFixtures.createTestCreator(email = "creator1@example.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@example.com", username = "creator2")

            val created = planService.createPlan(
                creator1.id.value,
                SubscriptionPlanRequest("기본 플랜", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택1"))
            )

            val updateRequest = UpdatePlanRequest(
                name = "변경된 플랜"
            )

            assertFailsWith<ForbiddenException> {
                planService.updatePlan(created.id, creator2.id.value, updateRequest)
            }
        }
    }

    @Test
    fun `플랜 삭제 - 관리 목록에서 빠지고 수정할 수 없으며 과거 구독이 보는 조회엔 남음`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val plan = planService.createPlan(creator.id.value, SubscriptionPlanRequest("지울 플랜", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택")))

            planService.deletePlan(plan.id, creator.id.value)

            val myPlans = planService.getMyPlans(creator.id.value, 1, 20)
            assertTrue(myPlans.items.none { it.id == plan.id })
            assertEquals(0, query { planRepository.countPlansByCreator(creator.id.value) })
            assertFailsWith<SubscriptionPlanNotFoundException> {
                planService.updatePlan(plan.id, creator.id.value, UpdatePlanRequest(isActive = true))
            }
            assertNotNull(query { planRepository.findPlanById(plan.id) })
        }
    }

    @Test
    fun `플랜 재활성화 실패 - 같은 티어 활성 플랜이 있으면 충돌`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val old = planService.createPlan(creator.id.value, SubscriptionPlanRequest("옛 플랜", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택")))
            planService.updatePlan(old.id, creator.id.value, UpdatePlanRequest(isActive = false))
            planService.createPlan(creator.id.value, SubscriptionPlanRequest("새 플랜", SubscriptionPlanTier.TIER1, "설명", "6000", listOf("혜택")))

            assertFailsWith<ConflictException> {
                planService.updatePlan(old.id, creator.id.value, UpdatePlanRequest(isActive = true))
            }
            assertEquals(1, query { planRepository.countActivePlansByCreator(creator.id.value) })
        }
    }

    @Test
    fun `플랜 수정 실패 - 크리에이터 해제된 사용자`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val plan = planService.createPlan(creator.id.value, SubscriptionPlanRequest("플랜", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택")))
            query { UserDao.findById(creator.id.value)!!.role = UserRole.USER }

            assertFailsWith<ForbiddenException> {
                planService.updatePlan(plan.id, creator.id.value, UpdatePlanRequest(isActive = true))
            }
        }
    }

    // ===== 혜택 관리 테스트 =====
}
