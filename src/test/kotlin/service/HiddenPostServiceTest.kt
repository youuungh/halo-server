package com.ninezero.service

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.features.social.data.HiddenPostRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.domain.HiddenPostService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HiddenPostServiceTest {

    private lateinit var hiddenPostService: HiddenPostService
    private lateinit var hiddenPostRepository: HiddenPostRepositoryImpl
    private lateinit var postRepository: PostRepositoryImpl
    private val cacheService: CacheService = mockk(relaxed = true)

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            hiddenPostRepository = HiddenPostRepositoryImpl()
            postRepository = PostRepositoryImpl()

            hiddenPostService = HiddenPostService(
                hiddenPostRepository = hiddenPostRepository,
                postRepository = postRepository,
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

    // ===== 숨기기 테스트 =====

    @Test
    fun `이미 숨긴 포스트를 다시 숨겨도 숨김 유지`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            hiddenPostService.hidePost(user.id.value, post.id.value)

            // When - 재요청
            val response = hiddenPostService.hidePost(
                userId = user.id.value,
                postId = post.id.value
            )

            // Then
            assertTrue(response.isHidden)
            assertEquals(listOf(post.id.value), query { hiddenPostRepository.findHiddenPostIds(user.id.value) })
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
                hiddenPostService.hidePost(
                    userId = user.id.value,
                    postId = nonExistentPostId
                )
            }
        }
    }

    @Test
    fun `관심없음 처리하면 숨긴 글이 남은 보는 사람의 캐시를 지운다`() {
        runBlocking {
            clearMocks(cacheService)
            val (viewer, author) = TestFixtures.createTestUsers(count = 2)
            val post = TestFixtures.createTestPost(userId = author.id.value)

            hiddenPostService.hidePost(viewer.id.value, post.id.value)

            coVerify(exactly = 1) { cacheService.deletePattern(CacheKeys.Patterns.userScoped(viewer.id.value)) }
        }
    }
}
