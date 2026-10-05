package com.ninezero.service

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.features.user.domain.BlockedUserService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockedUserServiceTest {

    private lateinit var blockedUserService: BlockedUserService
    private lateinit var blockedUserRepository: BlockedUserRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var followRepository: FollowRepositoryImpl
    private lateinit var cacheService: CacheService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            blockedUserRepository = BlockedUserRepositoryImpl()
            userRepository = UserRepositoryImpl()
            followRepository = FollowRepositoryImpl()
            cacheService = mockk(relaxed = true)

            blockedUserService = BlockedUserService(
                blockedUserRepository = blockedUserRepository,
                userRepository = userRepository,
                followRepository = followRepository,
                subscriptionRepository = SubscriptionRepositoryImpl(),
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

    // ===== 차단 토글 테스트 =====

    @Test
    fun `사용자 차단 해제 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val targetUser = TestFixtures.createTestUser(
                email = "target@test.com",
                username = "targetuser"
            )
            blockedUserService.toggleBlock(user.id.value, targetUser.id.value)

            // When - 차단 해제
            val response = blockedUserService.toggleBlock(
                userId = user.id.value,
                targetUserId = targetUser.id.value
            )

            // Then
            assertTrue(!response.isBlocked)
            assertEquals("차단을 해제했습니다.", response.message)
        }
    }

    @Test
    fun `자기 자신 차단 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When & Then
            assertFailsWith<InvalidInputException> {
                blockedUserService.toggleBlock(
                    userId = user.id.value,
                    targetUserId = user.id.value
                )
            }
        }
    }

    @Test
    fun `차단하면 양쪽 사용자의 개인화 캐시를 지운다`() {
        runBlocking {
            clearMocks(cacheService)
            val user = TestFixtures.createTestUser()
            val target = TestFixtures.createTestUser(email = "target@test.com", username = "targetuser")

            blockedUserService.toggleBlock(user.id.value, target.id.value)

            coVerify { cacheService.deletePattern(CacheKeys.Patterns.userScoped(user.id.value)) }
            coVerify { cacheService.deletePattern(CacheKeys.Patterns.userScoped(target.id.value)) }
        }
    }

    @Test
    fun `크리에이터가 구독자를 차단하면 그 구독의 자동 갱신이 꺼진다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val subscriber = TestFixtures.createTestUser()
            val subscriptionRepository = SubscriptionRepositoryImpl()
            val subscriptionId = query {
                val plan = SubscriptionPlanRepositoryImpl().createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "테스트 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[]"
                )
                val now = nowUtc()
                subscriptionRepository.createSubscription(
                    userId = subscriber.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = now,
                    expiresAt = now.date.plus(DatePeriod(days = 30))
                        .atTime(now.hour, now.minute, now.second, now.nanosecond),
                    autoRenew = true
                ).id.value
            }

            blockedUserService.toggleBlock(creator.id.value, subscriber.id.value)

            val autoRenew = query { subscriptionRepository.findSubscriptionById(subscriptionId)!!.autoRenew }
            assertFalse(autoRenew)
        }
    }

    // ===== 차단 목록 조회 테스트 =====

    @Test
    fun `차단한 사용자 목록 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val targetUsers = TestFixtures.createTestUsers(count = 5)

            // 3명 차단
            targetUsers.take(3).forEach { targetUser ->
                blockedUserService.toggleBlock(user.id.value, targetUser.id.value)
            }

            // When
            val response = blockedUserService.getBlockedUsers(
                userId = user.id.value,
                limit = 10
            )

            // Then
            assertEquals(3, response.items.size)
            assertEquals(false, response.hasNext)
        }
    }

    // ===== 차단 관계 확인 테스트 =====

    @Test
    fun `차단 관계 확인 - 차단함`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val targetUser = TestFixtures.createTestUser(
                email = "target@test.com",
                username = "targetuser"
            )
            blockedUserService.toggleBlock(user.id.value, targetUser.id.value)

            // When
            val response = blockedUserService.getBlockRelation(user.id.value, targetUser.id.value)

            // Then
            assertEquals(true, response.isBlockedByMe)
            assertEquals(false, response.isBlockedByOther)
            assertEquals(true, response.isBlocked)
        }
    }

    // ===== 자동 언팔로우 테스트 =====

    @Test
    fun `차단 시 자동 언팔로우 - 내가 팔로우한 경우`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val targetUser = TestFixtures.createTestUser(
                email = "target@test.com",
                username = "targetuser"
            )

            // user가 targetUser를 팔로우
            query { followRepository.createFollow(user.id.value, targetUser.id.value) }
            assertTrue(query { followRepository.isFollowing(user.id.value, targetUser.id.value) })

            // When - user가 targetUser 차단
            blockedUserService.toggleBlock(user.id.value, targetUser.id.value)

            // Then - 팔로우가 해제됨
            assertTrue(!query { followRepository.isFollowing(user.id.value, targetUser.id.value) })
        }
    }

    @Test
    fun `차단 시 자동 언팔로우 - 상대방이 나를 팔로우한 경우`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val targetUser = TestFixtures.createTestUser(
                email = "target@test.com",
                username = "targetuser"
            )

            // targetUser가 user를 팔로우
            query { followRepository.createFollow(targetUser.id.value, user.id.value) }
            assertTrue(query { followRepository.isFollowing(targetUser.id.value, user.id.value) })

            // When - user가 targetUser 차단
            blockedUserService.toggleBlock(user.id.value, targetUser.id.value)

            // Then - 팔로우가 해제됨
            assertTrue(!query { followRepository.isFollowing(targetUser.id.value, user.id.value) })
        }
    }
}
