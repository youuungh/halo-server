package com.ninezero.service

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.domain.FollowService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FollowServiceTest {

    private lateinit var followService: FollowService
    private lateinit var notificationService: NotificationService
    private lateinit var cacheService: CacheService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            // Mock 객체 생성 (알림은 성공으로 가정)
            notificationService = mockk(relaxed = true)
            coJustRun {
                notificationService.sendFollowNotification(any(), any())
            }

            cacheService = mockk(relaxed = true)

            // Service 초기화
            followService = FollowService(
                followRepository = FollowRepositoryImpl(),
                userRepository = UserRepositoryImpl(),
                blockedUserRepository = BlockedUserRepositoryImpl(),
                notificationService = notificationService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
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

    // ===== 팔로우 테스트 =====

    @Test
    fun `자기 자신 팔로우 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When & Then
            assertFailsWith<InvalidInputException> {
                followService.followUser(
                    followerId = user.id.value,
                    followingId = user.id.value
                )
            }
        }
    }

    @Test
    fun `존재하지 않는 사용자 팔로우 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val nonExistentUserId = 99999

            // When & Then
            assertFailsWith<UserNotFoundException> {
                followService.followUser(
                    followerId = user.id.value,
                    followingId = nonExistentUserId
                )
            }
        }
    }

    @Test
    fun `이미 팔로우 중인 사용자 다시 팔로우 실패`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            followService.followUser(user1.id.value, user2.id.value)

            // When & Then - 이미 팔로우한 사용자를 다시 팔로우
            assertFailsWith<ConflictException> {
                followService.followUser(
                    followerId = user1.id.value,
                    followingId = user2.id.value
                )
            }
        }
    }

    @Test
    fun `팔로우 알림 전송 확인`() {
        runBlocking {
            // Given
            val follower = TestFixtures.createTestUser(email = "follower@test.com", username = "follower")
            val following = TestFixtures.createTestUser(email = "following@test.com", username = "following")

            // When
            followService.followUser(follower.id.value, following.id.value)

            // Then - 알림 전송 확인
            coVerify {
                notificationService.sendFollowNotification(
                    followerId = follower.id.value,
                    followedId = following.id.value
                )
            }
        }
    }

    @Test
    fun `팔로우하면 양쪽 프로필 캐시를 지운다`() {
        runBlocking {
            clearMocks(cacheService)
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")

            followService.followUser(followerId = user1.id.value, followingId = user2.id.value)

            coVerify { cacheService.delete(CacheKeys.profile(user1.id.value)) }
            coVerify { cacheService.delete(CacheKeys.profile(user2.id.value)) }
        }
    }

    // ===== 언팔로우 테스트 =====

    @Test
    fun `팔로우하지 않은 사용자 언팔로우 실패`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")

            // When & Then - 팔로우하지 않은 사용자를 언팔로우
            assertFailsWith<ConflictException> {
                followService.unfollowUser(
                    followerId = user1.id.value,
                    followingId = user2.id.value
                )
            }
        }
    }

    // ===== 팔로워 목록 조회 테스트 =====

    @Test
    fun `팔로워 목록 조회`() {
        runBlocking {
            // Given
            val targetUser = TestFixtures.createTestUser(email = "target@test.com", username = "target")
            val followers = TestFixtures.createTestUsers(count = 3)

            // 3명이 targetUser를 팔로우
            followers.forEach { follower ->
                followService.followUser(follower.id.value, targetUser.id.value)
            }

            // When
            val response = followService.getFollowers(
                userId = targetUser.id.value,
                limit = 10
            )

            // Then
            assertEquals(3, response.items.size)
            assertEquals(3, response.totalCount)
        }
    }

    // ===== 팔로잉 목록 조회 테스트 =====

    @Test
    fun `팔로잉 목록 조회`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser(email = "user@test.com", username = "user")
            val targets = TestFixtures.createTestUsers(count = 4)

            // user가 4명을 팔로우
            targets.forEach { target ->
                followService.followUser(user.id.value, target.id.value)
            }

            // When
            val response = followService.getFollowing(
                userId = user.id.value,
                limit = 10
            )

            // Then
            assertEquals(4, response.items.size)
            assertEquals(4, response.totalCount)
        }
    }

    // ===== 상호 팔로우 확인 테스트 =====

    @Test
    fun `상호 팔로우 확인 - 일방적 팔로우`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            followService.followUser(user1.id.value, user2.id.value)

            // When
            val response = followService.getMutualFollow(user1.id.value, user2.id.value)

            // Then
            assertEquals(true, response.aFollowsB)
            assertEquals(false, response.bFollowsA)
            assertEquals(false, response.isMutualFollow)
        }
    }

    // ===== 팔로우 추천 테스트 =====

    @Test
    fun `팔로우 추천에서 차단 관계 사용자는 빠진다`() {
        runBlocking {
            val me = TestFixtures.createTestUser(email = "me@test.com", username = "me")
            val friend = TestFixtures.createTestUser(email = "friend@test.com", username = "friend")
            val blockedByMe = TestFixtures.createTestUser(email = "b1@test.com", username = "b1")
            val blocksMe = TestFixtures.createTestUser(email = "b2@test.com", username = "b2")
            val normal = TestFixtures.createTestUser(email = "n@test.com", username = "normal")
            followService.followUser(me.id.value, friend.id.value)
            listOf(blockedByMe, blocksMe, normal).forEach { followService.followUser(friend.id.value, it.id.value) }
            query {
                val blocks = BlockedUserRepositoryImpl()
                blocks.createBlock(me.id.value, blockedByMe.id.value)
                blocks.createBlock(blocksMe.id.value, me.id.value)
            }

            val ids = followService.getFollowRecommendations(me.id.value, limit = 10).map { it.id }.toSet()

            assertEquals(setOf(normal.id.value), ids)
        }
    }
}
