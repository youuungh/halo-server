package com.ninezero.service

import com.ninezero.core.common.exception.*
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.BookmarkRepositoryImpl
import com.ninezero.features.social.data.CommentRepositoryImpl
import com.ninezero.features.social.data.LikeRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.domain.LikeService
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
import kotlin.test.assertTrue

/**
 * LikeService 테스트
 *
 * 테스트 케이스: 20개
 * - 포스트 좋아요 토글: 5개
 * - 댓글 좋아요 토글: 4개
 * - 좋아요한 사용자 목록: 2개
 * - 좋아요 수 조회: 2개
 * - 좋아요 상태 확인: 4개
 * - 사용자 좋아요 목록: 2개
 * - 알림: 1개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LikeServiceTest {

    private lateinit var likeService: LikeService
    private lateinit var notificationService: NotificationService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            // Mock 객체 생성 (알림은 성공으로 가정)
            notificationService = mockk(relaxed = true)
            coJustRun {
                notificationService.sendLikePostNotification(any(), any(), any())
            }
            coJustRun {
                notificationService.sendLikeCommentNotification(any(), any(), any(), any())
            }

            // Service 초기화
            likeService = LikeService(
                likeRepository = LikeRepositoryImpl(),
                postRepository = PostRepositoryImpl(),
                commentRepository = CommentRepositoryImpl(),
                userRepository = UserRepositoryImpl(),
                notificationService = notificationService,
                bookmarkRepository = BookmarkRepositoryImpl(),
                postService = mockk(relaxed = true),
                followRepository = mockk(relaxed = true),
                blockedUserRepository = mockk(relaxed = true),
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
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

    // ===== 포스트 좋아요 토글 테스트 (5개) =====

    @Test
    fun `포스트 좋아요 추가 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When
            val response = likeService.togglePostLike(
                currentUserId = user.id.value,
                postId = post.id.value
            )

            // Then
            assertNotNull(response)
            assertTrue(response.isLiked)
            assertEquals(1, response.likeCount)
        }
    }

    @Test
    fun `포스트 좋아요 취소 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            likeService.togglePostLike(user.id.value, post.id.value)

            // When - 좋아요 취소
            val response = likeService.togglePostLike(
                currentUserId = user.id.value,
                postId = post.id.value
            )

            // Then
            assertNotNull(response)
            assertTrue(!response.isLiked)
            assertEquals(0, response.likeCount)
        }
    }

    @Test
    fun `여러 사용자가 포스트 좋아요 추가`() {
        runBlocking {
            // Given
            val users = TestFixtures.createTestUsers(count = 3)
            val post = TestFixtures.createTestPost(userId = users[0].id.value)

            // When - 3명이 좋아요
            users.forEach { user ->
                likeService.togglePostLike(user.id.value, post.id.value)
            }

            // Then
            val countResponse = likeService.getPostLikeCount(post.id.value)
            assertEquals(3, countResponse["count"])
        }
    }

    @Test
    fun `존재하지 않는 포스트 좋아요 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val nonExistentPostId = 99999

            // When & Then
            assertFailsWith<PostNotFoundException> {
                likeService.togglePostLike(
                    currentUserId = user.id.value,
                    postId = nonExistentPostId
                )
            }
        }
    }

    @Test
    fun `포스트 좋아요 알림 전송 확인`() {
        runBlocking {
            // Given
            val postOwner = TestFixtures.createTestUser(email = "owner@test.com", username = "owner")
            val liker = TestFixtures.createTestUser(email = "liker@test.com", username = "liker")
            val post = TestFixtures.createTestPost(userId = postOwner.id.value)

            // When
            likeService.togglePostLike(liker.id.value, post.id.value)

            // Then - 알림 전송 확인
            coVerify {
                notificationService.sendLikePostNotification(
                    postOwnerId = postOwner.id.value,
                    likerId = liker.id.value,
                    postId = post.id.value
                )
            }
        }
    }

    // ===== 댓글 좋아요 토글 테스트 (4개) =====

    @Test
    fun `댓글 좋아요 추가 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val comment = TestFixtures.createTestComment(
                userId = user.id.value,
                postId = post.id.value
            )

            // When
            val response = likeService.toggleCommentLike(
                currentUserId = user.id.value,
                commentId = comment.id.value
            )

            // Then
            assertNotNull(response)
            assertTrue(response.isLiked)
            assertEquals(1, response.likeCount)
        }
    }

    @Test
    fun `댓글 좋아요 취소 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val comment = TestFixtures.createTestComment(
                userId = user.id.value,
                postId = post.id.value
            )
            likeService.toggleCommentLike(user.id.value, comment.id.value)

            // When - 좋아요 취소
            val response = likeService.toggleCommentLike(
                currentUserId = user.id.value,
                commentId = comment.id.value
            )

            // Then
            assertNotNull(response)
            assertTrue(!response.isLiked)
            assertEquals(0, response.likeCount)
        }
    }

    @Test
    fun `여러 사용자가 댓글 좋아요 추가`() {
        runBlocking {
            // Given
            val users = TestFixtures.createTestUsers(count = 5)
            val post = TestFixtures.createTestPost(userId = users[0].id.value)
            val comment = TestFixtures.createTestComment(
                userId = users[0].id.value,
                postId = post.id.value
            )

            // When - 5명이 좋아요
            users.forEach { user ->
                likeService.toggleCommentLike(user.id.value, comment.id.value)
            }

            // Then - 좋아요 수 확인
            val countResponse = likeService.getCommentLikeCount(comment.id.value)
            assertEquals(5, countResponse["count"])
        }
    }

    @Test
    fun `존재하지 않는 댓글 좋아요 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val nonExistentCommentId = 99999

            // When & Then
            assertFailsWith<CommentNotFoundException> {
                likeService.toggleCommentLike(
                    currentUserId = user.id.value,
                    commentId = nonExistentCommentId
                )
            }
        }
    }

    // ===== 좋아요한 사용자 목록 조회 테스트 (2개) =====

    @Test
    fun `포스트 좋아요한 사용자 목록 조회`() {
        runBlocking {
            // Given
            val users = TestFixtures.createTestUsers(count = 3)
            val post = TestFixtures.createTestPost(userId = users[0].id.value)

            // 3명이 좋아요
            users.forEach { user ->
                likeService.togglePostLike(user.id.value, post.id.value)
            }

            // When
            val response = likeService.getPostLikedUsers(
                postId = post.id.value,
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
    fun `댓글 좋아요한 사용자 목록 조회`() {
        runBlocking {
            // Given
            val users = TestFixtures.createTestUsers(count = 2)
            val post = TestFixtures.createTestPost(userId = users[0].id.value)
            val comment = TestFixtures.createTestComment(
                userId = users[0].id.value,
                postId = post.id.value
            )

            // 2명이 좋아요
            users.forEach { user ->
                likeService.toggleCommentLike(user.id.value, comment.id.value)
            }

            // When
            val response = likeService.getCommentLikedUsers(
                commentId = comment.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(2, response.items.size)
            assertEquals(2, response.totalCount)
        }
    }

    // ===== 좋아요 수 조회 테스트 (2개) =====

    @Test
    fun `포스트 좋아요 수 조회`() {
        runBlocking {
            // Given
            val users = TestFixtures.createTestUsers(count = 4)
            val post = TestFixtures.createTestPost(userId = users[0].id.value)

            // 4명이 좋아요
            users.forEach { user ->
                likeService.togglePostLike(user.id.value, post.id.value)
            }

            // When
            val response = likeService.getPostLikeCount(post.id.value)

            // Then
            assertNotNull(response)
            assertEquals(4, response["count"])
        }
    }

    @Test
    fun `댓글 좋아요 수 조회`() {
        runBlocking {
            // Given
            val users = TestFixtures.createTestUsers(count = 3)
            val post = TestFixtures.createTestPost(userId = users[0].id.value)
            val comment = TestFixtures.createTestComment(
                userId = users[0].id.value,
                postId = post.id.value
            )

            // 3명이 좋아요
            users.forEach { user ->
                likeService.toggleCommentLike(user.id.value, comment.id.value)
            }

            // When
            val response = likeService.getCommentLikeCount(comment.id.value)

            // Then
            assertNotNull(response)
            assertEquals(3, response["count"])
        }
    }

    // ===== 좋아요 상태 확인 테스트 (4개) =====

    @Test
    fun `포스트 좋아요 상태 확인 - 좋아요한 경우`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            likeService.togglePostLike(user.id.value, post.id.value)

            // When
            val response = likeService.isPostLiked(user.id.value, post.id.value)

            // Then
            assertNotNull(response)
            assertEquals(true, response["isLiked"])
        }
    }

    @Test
    fun `포스트 좋아요 상태 확인 - 좋아요하지 않은 경우`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When
            val response = likeService.isPostLiked(user.id.value, post.id.value)

            // Then
            assertNotNull(response)
            assertEquals(false, response["isLiked"])
        }
    }

    @Test
    fun `댓글 좋아요 상태 확인 - 좋아요한 경우`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val comment = TestFixtures.createTestComment(
                userId = user.id.value,
                postId = post.id.value
            )
            likeService.toggleCommentLike(user.id.value, comment.id.value)

            // When
            val response = likeService.isCommentLiked(user.id.value, comment.id.value)

            // Then
            assertNotNull(response)
            assertEquals(true, response["isLiked"])
        }
    }

    @Test
    fun `댓글 좋아요 상태 확인 - 좋아요하지 않은 경우`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val comment = TestFixtures.createTestComment(
                userId = user.id.value,
                postId = post.id.value
            )

            // When
            val response = likeService.isCommentLiked(user.id.value, comment.id.value)

            // Then
            assertNotNull(response)
            assertEquals(false, response["isLiked"])
        }
    }

    // ===== 사용자가 좋아요한 포스트 목록 테스트 (2개) =====

    @Test
    fun `사용자가 좋아요한 포스트 목록 조회`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val posts = TestFixtures.createTestPosts(userId = user.id.value, count = 5)

            // 5개 포스트 중 3개만 좋아요
            posts.take(3).forEach { post ->
                likeService.togglePostLike(user.id.value, post.id.value)
            }

            // When
            val response = likeService.getUserLikedPosts(
                currentUserId = user.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(3, response["likedPosts"]?.size)
        }
    }

    @Test
    fun `좋아요 없는 사용자의 포스트 목록 조회`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When
            val response = likeService.getUserLikedPosts(
                currentUserId = user.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(0, response["likedPosts"]?.size)
        }
    }
}
