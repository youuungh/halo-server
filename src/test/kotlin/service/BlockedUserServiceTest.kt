package com.ninezero.service

import com.ninezero.core.common.util.query
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.features.user.domain.BlockedUserService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BlockedUserService 테스트
 *
 * 테스트 케이스: 12개
 * - 차단 토글: 4개
 * - 차단 목록 조회: 2개
 * - 차단 상태 확인: 2개
 * - 차단 수 조회: 2개
 * - 자동 언팔로우: 2개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockedUserServiceTest {

    private lateinit var blockedUserService: BlockedUserService
    private lateinit var blockedUserRepository: BlockedUserRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var followRepository: FollowRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            blockedUserRepository = BlockedUserRepositoryImpl()
            userRepository = UserRepositoryImpl()
            followRepository = FollowRepositoryImpl()

            blockedUserService = BlockedUserService(
                blockedUserRepository = blockedUserRepository,
                userRepository = userRepository,
                followRepository = followRepository,
                subscriptionRepository = SubscriptionRepositoryImpl()
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

    // ===== 차단 토글 테스트 (4개) =====

    @Test
    fun `사용자 차단 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val targetUser = TestFixtures.createTestUser(
                email = "target@test.com",
                username = "targetuser"
            )

            // When
            val response = blockedUserService.toggleBlock(
                userId = user.id.value,
                targetUserId = targetUser.id.value
            )

            // Then
            assertNotNull(response)
            assertTrue(response.isBlocked)
            assertEquals("사용자를 차단했습니다.", response.message)
        }
    }

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
            assertNotNull(response)
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
            assertFailsWith<IllegalArgumentException> {
                blockedUserService.toggleBlock(
                    userId = user.id.value,
                    targetUserId = user.id.value
                )
            }
        }
    }

    @Test
    fun `여러 사용자 차단`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val targetUsers = TestFixtures.createTestUsers(count = 3)

            // When - 3명 차단
            targetUsers.forEach { targetUser ->
                val response = blockedUserService.toggleBlock(user.id.value, targetUser.id.value)
                assertTrue(response.isBlocked)
            }

            // Then - 차단 수 확인
            val countResponse = blockedUserService.getBlockedUserCount(user.id.value)
            assertEquals(3, countResponse["count"])
        }
    }

    // ===== 차단 목록 조회 테스트 (2개) =====

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
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(3, response.totalCount)
            assertEquals(3, response.items.size)
        }
    }

    @Test
    fun `차단한 사용자 목록 조회 - 빈 목록`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When
            val response = blockedUserService.getBlockedUsers(
                userId = user.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(0, response.totalCount)
            assertTrue(response.items.isEmpty())
        }
    }

    // ===== 차단 상태 확인 테스트 (2개) =====

    @Test
    fun `차단 상태 확인 - 차단됨`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val targetUser = TestFixtures.createTestUser(
                email = "target@test.com",
                username = "targetuser"
            )
            blockedUserService.toggleBlock(user.id.value, targetUser.id.value)

            // When
            val response = blockedUserService.isBlocked(user.id.value, targetUser.id.value)

            // Then
            assertNotNull(response)
            assertEquals(true, response["isBlocked"])
        }
    }

    @Test
    fun `차단 상태 확인 - 차단되지 않음`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val targetUser = TestFixtures.createTestUser(
                email = "target@test.com",
                username = "targetuser"
            )

            // When
            val response = blockedUserService.isBlocked(user.id.value, targetUser.id.value)

            // Then
            assertNotNull(response)
            assertEquals(false, response["isBlocked"])
        }
    }

    // ===== 차단 수 조회 테스트 (2개) =====

    @Test
    fun `차단한 사용자 수 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val targetUsers = TestFixtures.createTestUsers(count = 5)

            // 5명 모두 차단
            targetUsers.forEach { targetUser ->
                blockedUserService.toggleBlock(user.id.value, targetUser.id.value)
            }

            // When
            val response = blockedUserService.getBlockedUserCount(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(5, response["count"])
        }
    }

    @Test
    fun `차단한 사용자 수 조회 - 0명`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When
            val response = blockedUserService.getBlockedUserCount(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(0, response["count"])
        }
    }

    // ===== 자동 언팔로우 테스트 (2개) =====

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
