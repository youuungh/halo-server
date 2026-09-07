package com.ninezero.service

import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.features.social.data.BookmarkRepositoryImpl
import com.ninezero.features.social.data.CommentRepositoryImpl
import com.ninezero.features.social.data.LikeRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.domain.BookmarkService
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BookmarkService 테스트
 *
 * 테스트 케이스: 10개
 * - 북마크 토글: 4개
 * - 북마크 목록 조회: 2개
 * - 북마크 상태 확인: 2개
 * - 북마크 수 조회: 2개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookmarkServiceTest {

    private lateinit var bookmarkService: BookmarkService
    private lateinit var bookmarkRepository: BookmarkRepositoryImpl
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var commentRepository: CommentRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var likeRepository: LikeRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            bookmarkRepository = BookmarkRepositoryImpl()
            postRepository = PostRepositoryImpl()
            commentRepository = CommentRepositoryImpl()
            userRepository = UserRepositoryImpl()
            likeRepository = LikeRepositoryImpl()

            bookmarkService = BookmarkService(
                bookmarkRepository = bookmarkRepository,
                postRepository = postRepository,
                commentRepository = commentRepository,
                userRepository = userRepository,
                likeRepository = likeRepository,
                postService = mockk(relaxed = true),
                followRepository = mockk(relaxed = true)
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

    // ===== 북마크 토글 테스트 (4개) =====

    @Test
    fun `북마크 추가 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When
            val response = bookmarkService.togglePostBookmark(
                userId = user.id.value,
                postId = post.id.value
            )

            // Then
            assertNotNull(response)
            assertTrue(response.isBookmarked)
            assertEquals("저장됨", response.message)
        }
    }

    @Test
    fun `북마크 취소 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            bookmarkService.togglePostBookmark(user.id.value, post.id.value)

            // When - 북마크 취소
            val response = bookmarkService.togglePostBookmark(
                userId = user.id.value,
                postId = post.id.value
            )

            // Then
            assertNotNull(response)
            assertTrue(!response.isBookmarked)
            assertEquals("저장 취소됨", response.message)
        }
    }

    @Test
    fun `여러 사용자가 같은 포스트 북마크`() {
        runBlocking {
            // Given
            val users = TestFixtures.createTestUsers(count = 3)
            val post = TestFixtures.createTestPost(userId = users[0].id.value)

            // When - 3명이 북마크
            users.forEach { user ->
                val response = bookmarkService.togglePostBookmark(user.id.value, post.id.value)
                assertTrue(response.isBookmarked)
            }

            // Then - 각 사용자마다 북마크 확인
            users.forEach { user ->
                val statusResponse = bookmarkService.isPostBookmarked(user.id.value, post.id.value)
                assertTrue(statusResponse["isBookmarked"] == true)
            }
        }
    }

    @Test
    fun `존재하지 않는 포스트 북마크 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val nonExistentPostId = 99999

            // When & Then
            assertFailsWith<PostNotFoundException> {
                bookmarkService.togglePostBookmark(
                    userId = user.id.value,
                    postId = nonExistentPostId
                )
            }
        }
    }

    // ===== 북마크 목록 조회 테스트 (2개) =====

    @Test
    fun `사용자 북마크 목록 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val posts = List(5) { i ->
                TestFixtures.createTestPost(
                    userId = user.id.value,
                    content = "포스트 ${i + 1}"
                )
            }

            // 3개 북마크
            posts.take(3).forEach { post ->
                bookmarkService.togglePostBookmark(user.id.value, post.id.value)
            }

            // When
            val response = bookmarkService.getUserBookmarks(
                userId = user.id.value,
                viewerUserId = user.id.value,
                page = 1,
                limit = 10,
                targetType = BookmarkTargetType.POST
            )

            // Then
            assertNotNull(response)
            assertEquals(3, response.totalCount)
            assertEquals(3, response.items.size)
            val targetIds = response.items.map { it.targetId }
            assertTrue(targetIds.contains(posts[0].id.value))
            assertTrue(targetIds.contains(posts[1].id.value))
            assertTrue(targetIds.contains(posts[2].id.value))
        }
    }

    @Test
    fun `북마크 목록 조회 - 빈 목록`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When
            val response = bookmarkService.getUserBookmarks(
                userId = user.id.value,
                viewerUserId = user.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(0, response.totalCount)
            assertTrue(response.items.isEmpty())
        }
    }

    // ===== 북마크 상태 확인 테스트 (2개) =====

    @Test
    fun `북마크 상태 확인 - 북마크됨`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            bookmarkService.togglePostBookmark(user.id.value, post.id.value)

            // When
            val response = bookmarkService.isPostBookmarked(user.id.value, post.id.value)

            // Then
            assertNotNull(response)
            assertEquals(true, response["isBookmarked"])
        }
    }

    @Test
    fun `북마크 상태 확인 - 북마크 안됨`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When
            val response = bookmarkService.isPostBookmarked(user.id.value, post.id.value)

            // Then
            assertNotNull(response)
            assertEquals(false, response["isBookmarked"])
        }
    }

    // ===== 북마크 수 조회 테스트 (2개) =====

    @Test
    fun `사용자 북마크 수 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val posts = List(5) { i ->
                TestFixtures.createTestPost(
                    userId = user.id.value,
                    content = "포스트 ${i + 1}"
                )
            }

            // 5개 모두 북마크
            posts.forEach { post ->
                bookmarkService.togglePostBookmark(user.id.value, post.id.value)
            }

            // When
            val response = bookmarkService.getUserBookmarkCount(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(5, response["count"])
        }
    }

    @Test
    fun `북마크 수 조회 - 0개`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When
            val response = bookmarkService.getUserBookmarkCount(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(0, response["count"])
        }
    }
}
