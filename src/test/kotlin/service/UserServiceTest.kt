package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.features.user.domain.UserService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UserService 테스트
 *
 * 테스트 케이스: 12개
 * - 사용자 조회: 3개
 * - 사용자 검색: 3개
 * - 사용자명 중복 확인: 2개
 * - 사용자 목록: 2개
 * - 사용자 통계: 2개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserServiceTest {

    private lateinit var userService: UserService
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var cacheService: CacheService
    private lateinit var blockedUserRepository: BlockedUserRepository

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            userRepository = UserRepositoryImpl()
            cacheService = mockk(relaxed = true)
            blockedUserRepository = mockk(relaxed = true)

            // CacheService mock 설정
            coEvery { cacheService.get<Any>(any(), any()) } returns null
            coEvery { blockedUserRepository.findBlockRelatedUserIds(any()) } returns emptySet()

            userService = UserService(userRepository, cacheService, blockedUserRepository)
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

    // ===== 사용자 조회 테스트 (3개) =====

    @Test
    fun `사용자 조회 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser(
                email = "test@example.com",
                username = "testuser"
            )

            val response = userService.getUserById(user.id.value)

            assertNotNull(response)
            assertEquals(user.id.value, response.id)
            assertEquals("testuser", response.username)
        }
    }

    @Test
    fun `사용자 조회 실패 - 존재하지 않는 사용자`() {
        runBlocking {
            assertFailsWith<UserNotFoundException> {
                userService.getUserById(99999)
            }
        }
    }

    @Test
    fun `크리에이터 조회 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator(
                email = "creator@example.com",
                username = "creator"
            )

            val response = userService.getUserById(creator.id.value)

            assertNotNull(response)
            assertEquals(creator.id.value, response.id)
            assertEquals("creator", response.username)
        }
    }

    // ===== 사용자 검색 테스트 (3개) =====

    @Test
    fun `사용자 검색 성공 - 사용자명으로`() {
        runBlocking {
            TestFixtures.createTestUser(email = "user1@example.com", username = "testuser1")
            TestFixtures.createTestUser(email = "user2@example.com", username = "testuser2")
            TestFixtures.createTestUser(email = "user3@example.com", username = "other")

            val response = userService.searchUsers("testuser", page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(2, response.items.size)
            assertTrue(response.items.all { it.username.contains("testuser") })
        }
    }

    @Test
    fun `사용자 검색 - 결과 없음`() {
        runBlocking {
            TestFixtures.createTestUser(email = "user1@example.com", username = "testuser")

            val response = userService.searchUsers("존재하지않는사용자", page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(0, response.items.size)
        }
    }

    @Test
    fun `사용자 검색 - 페이지네이션`() {
        runBlocking {
            // 5명 생성
            repeat(5) { i ->
                TestFixtures.createTestUser(
                    email = "user$i@example.com",
                    username = "testuser$i"
                )
            }

            // 첫 페이지 (2개)
            val page1 = userService.searchUsers("testuser", page = 1, limit = 2)
            assertEquals(2, page1.items.size)

            // 두 번째 페이지 (2개)
            val page2 = userService.searchUsers("testuser", page = 2, limit = 2)
            assertEquals(2, page2.items.size)

            // 세 번째 페이지 (1개)
            val page3 = userService.searchUsers("testuser", page = 3, limit = 2)
            assertEquals(1, page3.items.size)
        }
    }

    // ===== 사용자명 중복 확인 테스트 (2개) =====

    @Test
    fun `사용자명 중복 확인 - 사용 가능`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test@example.com", username = "testuser")

            val response = userService.checkUsernameAvailability("newuser")

            assertNotNull(response)
            assertEquals(true, response["available"])
        }
    }

    @Test
    fun `사용자명 중복 확인 - 이미 사용 중`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test@example.com", username = "testuser")

            val response = userService.checkUsernameAvailability("testuser")

            assertNotNull(response)
            assertEquals(false, response["available"])
        }
    }

    // ===== 사용자 목록 테스트 (2개) =====

    @Test
    fun `모든 사용자 목록 조회 성공`() {
        runBlocking {
            // 3명 생성
            TestFixtures.createTestUsers(3)

            val response = userService.getAllUsers(page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(3, response.items.size)
            assertEquals(3, response.totalCount)
        }
    }

    @Test
    fun `모든 사용자 목록 조회 - 페이지네이션`() {
        runBlocking {
            // 5명 생성
            TestFixtures.createTestUsers(5)

            val page1 = userService.getAllUsers(page = 1, limit = 2)
            assertEquals(2, page1.items.size)
            assertEquals(5, page1.totalCount)

            val page2 = userService.getAllUsers(page = 2, limit = 2)
            assertEquals(2, page2.items.size)

            val page3 = userService.getAllUsers(page = 3, limit = 2)
            assertEquals(1, page3.items.size)
        }
    }

    // ===== 사용자 통계 테스트 (2개) =====

    @Test
    fun `사용자 통계 조회 성공`() {
        runBlocking {
            // 일반 사용자 3명
            TestFixtures.createTestUsers(3)

            // 크리에이터 2명
            TestFixtures.createTestCreator(email = "creator1@example.com", username = "creator1")
            TestFixtures.createTestCreator(email = "creator2@example.com", username = "creator2")

            // 관리자 1명
            TestFixtures.createTestAdmin()

            val response = userService.getUserStatistics()

            assertNotNull(response)
            assertEquals(6, response.totalUsers)
            assertEquals(2, response.totalCreators)
            assertEquals(1, response.totalAdmins)
        }
    }

    @Test
    fun `사용자 통계 - 신규 가입자 확인`() {
        runBlocking {
            // 오늘 가입한 사용자
            TestFixtures.createTestUsers(2)

            val response = userService.getUserStatistics()

            assertNotNull(response)
            assertTrue(response.newUsersToday >= 2)
            assertTrue(response.newUsersThisWeek >= 2)
            assertTrue(response.newUsersThisMonth >= 2)
        }
    }
}
