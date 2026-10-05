package com.ninezero.service

import com.ninezero.core.common.exception.InvalidUsernameException
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.user.RefreshTokenTable
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.RefreshTokenRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.features.user.domain.UserService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.minusDays
import com.ninezero.helper.TestFixtures.plusDays
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserServiceTest {

    private lateinit var userService: UserService
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var blockedUserRepository: BlockedUserRepository

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            userRepository = UserRepositoryImpl()
            blockedUserRepository = mockk(relaxed = true)

            coEvery { blockedUserRepository.findBlockRelatedUserIds(any()) } returns emptySet()

            userService = UserService(userRepository, blockedUserRepository)
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

    // ===== 사용자 검색 테스트 =====

    @Test
    fun `사용자 검색 성공 - 사용자명으로`() {
        runBlocking {
            TestFixtures.createTestUser(email = "user1@example.com", username = "testuser1")
            TestFixtures.createTestUser(email = "user2@example.com", username = "testuser2")
            TestFixtures.createTestUser(email = "user3@example.com", username = "other")

            val response = userService.searchUsers("testuser", page = 1, limit = 10)

            assertEquals(2, response.items.size)
            assertTrue(response.items.all { it.username.contains("testuser") })
        }
    }

    // ===== 사용자명 중복 확인 테스트 =====

    @Test
    fun `사용자명 중복 확인 - 이미 사용 중`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test@example.com", username = "testuser")

            val response = userService.checkUsernameAvailability("testuser")

            assertEquals(false, response.available)
        }
    }

    @Test
    fun `사용자명 중복 확인 - 가입 규칙에 맞지 않으면 거절`() {
        runBlocking {
            assertFailsWith<InvalidUsernameException> { userService.checkUsernameAvailability("abc") }  // 5자 미만
            assertFailsWith<InvalidUsernameException> { userService.checkUsernameAvailability("user-name") }  // 허용 안 되는 문자
        }
    }

    // ===== 사용자 목록 테스트 =====

    @Test
    fun `모든 사용자 목록 조회 - 페이지네이션`() {
        runBlocking {
            TestFixtures.createTestUsers(5)  // 5명 생성

            val page1 = userService.getAllUsers(page = 1, limit = 2)
            assertEquals(2, page1.items.size)
            assertEquals(5, page1.totalCount)

            val page2 = userService.getAllUsers(page = 2, limit = 2)
            assertEquals(2, page2.items.size)

            val page3 = userService.getAllUsers(page = 3, limit = 2)
            assertEquals(1, page3.items.size)
        }
    }

    // ===== 사용자 통계 테스트 =====

    @Test
    fun `사용자 통계 - 활성 사용자는 최근 30일 안에 토큰을 받은 사용자만 센다`() {
        runBlocking {
            val active = TestFixtures.createTestUser(email = "active@example.com", username = "activeuser")
            val stale = TestFixtures.createTestUser(email = "stale@example.com", username = "staleuser")
            TestFixtures.createTestUser(email = "never@example.com", username = "neveruser")
            val refreshTokens = RefreshTokenRepositoryImpl()
            query {
                refreshTokens.create(active.id.value, "session-active", "token-active", nowUtc().plusDays(30))
                val old = refreshTokens.create(stale.id.value, "session-stale", "token-stale", nowUtc().plusDays(30))
                RefreshTokenTable.update({ RefreshTokenTable.id eq old.id }) { it[createdAt] = nowUtc().minusDays(40) }
            }

            val response = userService.getUserStatistics()

            assertEquals(3, response.totalUsers)
            assertEquals(1, response.activeUsers)
        }
    }
}
