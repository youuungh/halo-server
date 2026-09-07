package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.*
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.subscription.domain.SubscriptionPlanService
import com.ninezero.features.subscription.presentation.models.request.SubscriptionPlanRequest
import com.ninezero.features.subscription.presentation.models.request.UpdatePlanRequest
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SubscriptionPlanService 테스트
 *
 * 테스트 케이스: 18개
 * - 플랜 생성: 6개
 * - 플랜 조회: 4개
 * - 플랜 수정: 4개
 * - 플랜 비활성화: 2개
 * - 혜택 관리: 2개
 */
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

    // ===== 플랜 생성 테스트 (6개) =====

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

            assertNotNull(response)
            assertEquals("기본 플랜", response.name)
            assertEquals(SubscriptionPlanTier.TIER1, response.tier)
            assertEquals(BigDecimal(response.price).compareTo(BigDecimal("5000")), 0)
            assertTrue(response.isActive)
        }
    }

    @Test
    fun `플랜 생성 성공 - TIER2`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = SubscriptionPlanRequest(
                name = "프리미엄 플랜",
                tier = SubscriptionPlanTier.TIER2,
                description = "프리미엄 구독 플랜입니다",
                price = "10000",
                benefits = listOf("프리미엄 혜택1", "프리미엄 혜택2")
            )

            val response = planService.createPlan(creator.id.value, request)

            assertNotNull(response)
            assertEquals("프리미엄 플랜", response.name)
            assertEquals(SubscriptionPlanTier.TIER2, response.tier)
            assertEquals(BigDecimal(response.price).compareTo(BigDecimal("10000")), 0)
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
    fun `플랜 생성 실패 - 잘못된 가격 (음수)`() {
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
        }
    }

    // ===== 플랜 조회 테스트 (4개) =====

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

            assertNotNull(response)
            assertEquals(2, response.items.size)
            assertEquals(2, response.totalCount)
        }
    }

    @Test
    fun `내가 만든 플랜 목록 조회 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("플랜1", SubscriptionPlanTier.TIER1, "설명1", "5000", listOf("혜택1"))
            )

            val response = planService.getMyPlans(creator.id.value, page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(1, response.items.size)
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
    fun `플랜 상세 조회 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val created = planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("기본 플랜", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택1"))
            )

            val response = planService.getPlanById(created.id)

            assertNotNull(response)
            assertEquals(created.id, response.id)
            assertEquals("기본 플랜", response.name)
        }
    }

    // ===== 플랜 수정 테스트 (4개) =====

    @Test
    fun `플랜 수정 성공 - 이름과 설명 변경`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val created = planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("원래 플랜", SubscriptionPlanTier.TIER1, "원래 설명", "5000", listOf("혜택1"))
            )

            val updateRequest = UpdatePlanRequest(
                name = "변경된 플랜",
                description = "변경된 설명"
            )

            val updated = planService.updatePlan(created.id, creator.id.value, updateRequest)

            assertNotNull(updated)
            assertEquals("변경된 플랜", updated.name)
            assertEquals("변경된 설명", updated.description)
        }
    }

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

            assertNotNull(updated)
            assertEquals(BigDecimal(updated.price).compareTo(BigDecimal("7000")), 0)
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
    fun `플랜 수정 실패 - FREE 티어로 변경 불가`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val created = planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("기본 플랜", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택1"))
            )

            val updateRequest = UpdatePlanRequest(
                tier = SubscriptionPlanTier.FREE
            )

            assertFailsWith<ValidationException> {
                planService.updatePlan(created.id, creator.id.value, updateRequest)
            }
        }
    }

    // ===== 플랜 비활성화 테스트 (2개) =====

    @Test
    fun `플랜 비활성화 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val created = planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest("기본 플랜", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택1"))
            )

            val message = planService.deactivatePlan(created.id, creator.id.value)

            assertNotNull(message)
            assertTrue(message.contains("비활성화") || message.contains("DEACTIVATE"))

            // 비활성화된 플랜 조회 시도 (실패)
            assertFailsWith<ValidationException> {
                planService.getPlanById(created.id)
            }
        }
    }

    @Test
    fun `플랜 비활성화 실패 - 권한 없음`() {
        runBlocking {
            val creator1 = TestFixtures.createTestCreator(email = "creator1@example.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@example.com", username = "creator2")

            val created = planService.createPlan(
                creator1.id.value,
                SubscriptionPlanRequest("기본 플랜", SubscriptionPlanTier.TIER1, "설명", "5000", listOf("혜택1"))
            )

            assertFailsWith<ForbiddenException> {
                planService.deactivatePlan(created.id, creator2.id.value)
            }
        }
    }

    // ===== 혜택 관리 테스트 (2개) =====

    @Test
    fun `플랜 혜택 수정 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val created = planService.createPlan(
                creator.id.value,
                SubscriptionPlanRequest(
                    "기본 플랜",
                    SubscriptionPlanTier.TIER1,
                    "설명",
                    "5000",
                    listOf("혜택1", "혜택2")
                )
            )

            val updateRequest = UpdatePlanRequest(
                benefits = listOf("새로운 혜택1", "새로운 혜택2", "새로운 혜택3")
            )

            val updated = planService.updatePlan(created.id, creator.id.value, updateRequest)

            assertNotNull(updated)
            assertEquals(3, updated.benefits.size)
        }
    }

    @Test
    fun `플랜 생성 시 혜택 검증`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = SubscriptionPlanRequest(
                name = "기본 플랜",
                tier = SubscriptionPlanTier.TIER1,
                description = "설명",
                price = "5000",
                benefits = listOf("혜택1", "혜택2", "혜택3")
            )

            val response = planService.createPlan(creator.id.value, request)

            assertNotNull(response)
            assertEquals(3, response.benefits.size)
            assertTrue(response.benefits.contains("혜택1"))
            assertTrue(response.benefits.contains("혜택2"))
            assertTrue(response.benefits.contains("혜택3"))
        }
    }
}