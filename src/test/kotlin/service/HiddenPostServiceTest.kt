package com.ninezero.service

import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.features.social.data.HiddenPostRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.domain.HiddenPostService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HiddenPostService 테스트
 *
 * 테스트 케이스: 10개
 * - 숨기기 토글: 4개
 * - 숨긴 포스트 목록 조회: 2개
 * - 숨김 상태 확인: 2개
 * - 숨긴 포스트 수 조회: 2개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HiddenPostServiceTest {

    private lateinit var hiddenPostService: HiddenPostService
    private lateinit var hiddenPostRepository: HiddenPostRepositoryImpl
    private lateinit var postRepository: PostRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            hiddenPostRepository = HiddenPostRepositoryImpl()
            postRepository = PostRepositoryImpl()

            hiddenPostService = HiddenPostService(
                hiddenPostRepository = hiddenPostRepository,
                postRepository = postRepository
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

    // ===== 숨기기 토글 테스트 (4개) =====

    @Test
    fun `포스트 숨기기 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When
            val response = hiddenPostService.toggleHidePost(
                userId = user.id.value,
                postId = post.id.value
            )

            // Then
            assertNotNull(response)
            assertTrue(response.isHidden)
            assertEquals("관심없음 처리됨", response.message)
        }
    }

    @Test
    fun `포스트 숨기기 취소 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            hiddenPostService.toggleHidePost(user.id.value, post.id.value)

            // When - 숨기기 취소
            val response = hiddenPostService.toggleHidePost(
                userId = user.id.value,
                postId = post.id.value
            )

            // Then
            assertNotNull(response)
            assertTrue(!response.isHidden)
            assertEquals("관심없음 취소됨", response.message)
        }
    }

    @Test
    fun `여러 사용자가 같은 포스트 숨기기`() {
        runBlocking {
            // Given
            val users = TestFixtures.createTestUsers(count = 3)
            val post = TestFixtures.createTestPost(userId = users[0].id.value)

            // When - 3명이 숨기기
            users.forEach { user ->
                val response = hiddenPostService.toggleHidePost(user.id.value, post.id.value)
                assertTrue(response.isHidden)
            }

            // Then - 각 사용자마다 숨김 상태 확인
            users.forEach { user ->
                val statusResponse = hiddenPostService.isHidden(user.id.value, post.id.value)
                assertTrue(statusResponse["isHidden"] == true)
            }
        }
    }

    @Test
    fun `존재하지 않는 포스트 숨기기 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val nonExistentPostId = 99999

            // When & Then
            assertFailsWith<PostNotFoundException> {
                hiddenPostService.toggleHidePost(
                    userId = user.id.value,
                    postId = nonExistentPostId
                )
            }
        }
    }

    // ===== 숨긴 포스트 목록 조회 테스트 (2개) =====

    @Test
    fun `사용자 숨긴 포스트 목록 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val posts = List(5) { i ->
                TestFixtures.createTestPost(
                    userId = user.id.value,
                    content = "포스트 ${i + 1}"
                )
            }

            // 3개 숨기기
            posts.take(3).forEach { post ->
                hiddenPostService.toggleHidePost(user.id.value, post.id.value)
            }

            // When
            val response = hiddenPostService.getUserHiddenPosts(
                userId = user.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(3, response.totalCount)
            assertEquals(3, response.items.size)
            assertTrue(response.items.contains(posts[0].id.value))
            assertTrue(response.items.contains(posts[1].id.value))
            assertTrue(response.items.contains(posts[2].id.value))
        }
    }

    @Test
    fun `숨긴 포스트 목록 조회 - 빈 목록`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When
            val response = hiddenPostService.getUserHiddenPosts(
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

    // ===== 숨김 상태 확인 테스트 (2개) =====

    @Test
    fun `숨김 상태 확인 - 숨겨짐`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            hiddenPostService.toggleHidePost(user.id.value, post.id.value)

            // When
            val response = hiddenPostService.isHidden(user.id.value, post.id.value)

            // Then
            assertNotNull(response)
            assertEquals(true, response["isHidden"])
        }
    }

    @Test
    fun `숨김 상태 확인 - 숨겨지지 않음`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When
            val response = hiddenPostService.isHidden(user.id.value, post.id.value)

            // Then
            assertNotNull(response)
            assertEquals(false, response["isHidden"])
        }
    }

    // ===== 숨긴 포스트 수 조회 테스트 (2개) =====

    @Test
    fun `사용자 숨긴 포스트 수 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val posts = List(5) { i ->
                TestFixtures.createTestPost(
                    userId = user.id.value,
                    content = "포스트 ${i + 1}"
                )
            }

            // 5개 모두 숨기기
            posts.forEach { post ->
                hiddenPostService.toggleHidePost(user.id.value, post.id.value)
            }

            // When
            val response = hiddenPostService.getUserHiddenPostCount(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(5, response["count"])
        }
    }

    @Test
    fun `숨긴 포스트 수 조회 - 0개`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When
            val response = hiddenPostService.getUserHiddenPostCount(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(0, response["count"])
        }
    }
}
