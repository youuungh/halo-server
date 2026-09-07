package com.ninezero.service

import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.domain.FollowService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
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
import kotlin.test.assertNotNull

/**
 * FollowService 테스트
 *
 * 테스트 케이스: 20개
 * - 팔로우: 5개
 * - 언팔로우: 2개
 * - 팔로워 목록 조회: 2개
 * - 팔로잉 목록 조회: 2개
 * - 팔로우 통계: 1개
 * - 팔로우 관계 확인: 2개
 * - 상호 팔로우: 2개
 * - 팔로워/팔로잉 수: 2개
 * - 사용자 요약 정보: 1개
 * - 알림: 1개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FollowServiceTest {

    private lateinit var followService: FollowService
    private lateinit var notificationService: NotificationService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            // Mock 객체 생성 (알림은 성공으로 가정)
            notificationService = mockk(relaxed = true)
            coJustRun {
                notificationService.sendFollowNotification(any(), any())
            }

            // Service 초기화
            followService = FollowService(
                followRepository = FollowRepositoryImpl(),
                userRepository = UserRepositoryImpl(),
                blockedUserRepository = BlockedUserRepositoryImpl(),
                notificationService = notificationService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
                cacheService = mockk(relaxed = true)
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

    // ===== 팔로우 테스트 (5개) =====

    @Test
    fun `사용자 팔로우 테스트`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")

            // When
            val response = followService.followUser(
                followerId = user1.id.value,
                followingId = user2.id.value
            )

            // Then
            assertNotNull(response)
            assertEquals(1, response.followerCount) // user2의 팔로워 수
            assertEquals(1, response.followingCount) // user1의 팔로잉 수
        }
    }

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

    // ===== 언팔로우 테스트 (2개) =====

    @Test
    fun `사용자 언팔로우 테스트`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            followService.followUser(user1.id.value, user2.id.value)

            // When
            val response = followService.unfollowUser(
                followerId = user1.id.value,
                followingId = user2.id.value
            )

            // Then
            assertNotNull(response)
            assertEquals(0, response.followerCount)
            assertEquals(0, response.followingCount)
        }
    }

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

    // ===== 팔로워 목록 조회 테스트 (2개) =====

    @Test
    fun `팔로워 목록 조회 테스트`() {
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
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(3, response.items.size)
            assertEquals(3, response.totalCount)
        }
    }

    @Test
    fun `팔로워 없는 사용자 조회`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When
            val response = followService.getFollowers(
                userId = user.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(0, response.items.size)
            assertEquals(0, response.totalCount)
        }
    }

    // ===== 팔로잉 목록 조회 테스트 (2개) =====

    @Test
    fun `팔로잉 목록 조회 테스트`() {
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
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(4, response.items.size)
            assertEquals(4, response.totalCount)
        }
    }

    @Test
    fun `팔로잉 없는 사용자 조회`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When
            val response = followService.getFollowing(
                userId = user.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(0, response.items.size)
            assertEquals(0, response.totalCount)
        }
    }

    // ===== 팔로우 통계 조회 테스트 (1개) =====

    @Test
    fun `팔로우 통계 조회 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser(email = "user@test.com", username = "user")
            val users = TestFixtures.createTestUsers(count = 5)

            // 2명이 user를 팔로우 (팔로워)
            users.take(2).forEach { follower ->
                followService.followUser(follower.id.value, user.id.value)
            }

            // user가 3명을 팔로우 (팔로잉)
            users.takeLast(3).forEach { following ->
                followService.followUser(user.id.value, following.id.value)
            }

            // When
            val response = followService.getFollowStats(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(2, response.followerCount)
            assertEquals(3, response.followingCount)
        }
    }

    // ===== 팔로우 관계 확인 테스트 (2개) =====

    @Test
    fun `팔로우 관계 확인 - 팔로우 중`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            followService.followUser(user1.id.value, user2.id.value)

            // When
            val response = followService.getFollowStatus(user1.id.value, user2.id.value)

            // Then
            assertNotNull(response)
            assertEquals(true, response["isFollowing"])
        }
    }

    @Test
    fun `팔로우 관계 확인 - 팔로우 안함`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")

            // When
            val response = followService.getFollowStatus(user1.id.value, user2.id.value)

            // Then
            assertNotNull(response)
            assertEquals(false, response["isFollowing"])
        }
    }

    // ===== 상호 팔로우 확인 테스트 (2개) =====

    @Test
    fun `상호 팔로우 확인 - 맞팔`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            followService.followUser(user1.id.value, user2.id.value)
            followService.followUser(user2.id.value, user1.id.value)

            // When
            val response = followService.getMutualFollow(user1.id.value, user2.id.value)

            // Then
            assertNotNull(response)
            assertEquals(true, response["aFollowsB"])
            assertEquals(true, response["bFollowsA"])
            assertEquals(true, response["isMutualFollow"])
        }
    }

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
            assertNotNull(response)
            assertEquals(true, response["aFollowsB"])
            assertEquals(false, response["bFollowsA"])
            assertEquals(false, response["isMutualFollow"])
        }
    }

    // ===== 팔로워/팔로잉 수 조회 테스트 (2개) =====

    @Test
    fun `팔로워 수 조회`() {
        runBlocking {
            // Given
            val targetUser = TestFixtures.createTestUser(email = "target@test.com", username = "target")
            val followers = TestFixtures.createTestUsers(count = 3)

            followers.forEach { follower ->
                followService.followUser(follower.id.value, targetUser.id.value)
            }

            // When
            val response = followService.getFollowerCount(targetUser.id.value)

            // Then
            assertNotNull(response)
            assertEquals(3, response["count"])
        }
    }

    @Test
    fun `팔로잉 수 조회`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser(email = "user@test.com", username = "user")
            val targets = TestFixtures.createTestUsers(count = 4)

            targets.forEach { target ->
                followService.followUser(user.id.value, target.id.value)
            }

            // When
            val response = followService.getFollowingCount(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(4, response["count"])
        }
    }

    // ===== 사용자 팔로우 요약 정보 테스트 (1개) =====

    @Test
    fun `사용자 팔로우 요약 정보 조회`() {
        runBlocking {
            // Given
            val targetUser = TestFixtures.createTestUser(email = "target@test.com", username = "target")
            val currentUser = TestFixtures.createTestUser(email = "current@test.com", username = "current")
            val otherUser = TestFixtures.createTestUser(email = "other@test.com", username = "other")

            // currentUser -> targetUser 팔로우
            followService.followUser(currentUser.id.value, targetUser.id.value)
            // otherUser -> targetUser 팔로우
            followService.followUser(otherUser.id.value, targetUser.id.value)
            // targetUser -> currentUser 팔로우 (맞팔)
            followService.followUser(targetUser.id.value, currentUser.id.value)

            // When
            val response = followService.getUserFollowSummary(
                targetUserId = targetUser.id.value,
                currentUserId = currentUser.id.value
            )

            // Then
            assertNotNull(response)
            assertEquals(2, response["followerCount"]) // 2명이 팔로우
            assertEquals(1, response["followingCount"]) // 1명을 팔로우
            assertEquals(true, response["isFollowedBy"]) // 현재 사용자가 팔로우 중
            assertEquals(true, response["isFollowing"]) // 상대방도 팔로우 중
            assertEquals(true, response["isMutualFollow"]) // 맞팔
        }
    }
}
