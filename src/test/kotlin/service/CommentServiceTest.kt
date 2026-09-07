package com.ninezero.service

import com.ninezero.core.common.exception.*
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.BookmarkRepositoryImpl
import com.ninezero.features.social.data.CommentRepositoryImpl
import com.ninezero.features.social.data.LikeRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.domain.CommentService
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
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
 * CommentService 테스트
 *
 * 테스트 케이스: 20개
 * - 댓글 작성: 7개
 * - 댓글 목록 조회: 3개
 * - 댓글 수정: 4개
 * - 댓글 삭제: 3개
 * - 대댓글: 2개
 * - 알림: 1개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommentServiceTest {

    private lateinit var commentService: CommentService
    private lateinit var notificationService: NotificationService
    private lateinit var fileUploadService: FileUploadService
    private lateinit var imageProcessingService: ImageProcessingService
    private lateinit var videoProcessingService: VideoProcessingService
    private lateinit var postService: PostService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            // mock
            notificationService = mockk(relaxed = true)
            coEvery {
                notificationService.sendCommentNotification(any(), any(), any(), any())
            } returns null
            coEvery {
                notificationService.sendReplyNotification(any(), any(), any(), any(), any())
            } returns null
            coEvery {
                notificationService.sendMentionNotification(any(), any(), any(), any(), any())
            } returns null

            // createComment의 잠긴 글 게이트가 checkPostAccess를 호출한다 — relaxed 기본값(false)이면
            // 모든 댓글 작성이 403이므로, FREE·비밀 아님 픽스처의 실제 판정과 동일하게 true로 스텁.
            postService = mockk(relaxed = true)
            coEvery { postService.checkPostAccess(any(), any()) } returns true

            // Service 초기화
            fileUploadService = mockk(relaxed = true)
            imageProcessingService = mockk(relaxed = true)
            videoProcessingService = mockk(relaxed = true)
            commentService = CommentService(
                commentRepository = CommentRepositoryImpl(),
                postRepository = PostRepositoryImpl(),
                likeRepository = LikeRepositoryImpl(),
                bookmarkRepository = BookmarkRepositoryImpl(),
                userRepository = UserRepositoryImpl(),
                blockedUserRepository = BlockedUserRepositoryImpl(),
                notificationService = notificationService,
                postService = postService,
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
                videoProcessingService = videoProcessingService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
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

    // ===== 댓글 작성 테스트 (7개) =====

    @Test
    fun `댓글 작성 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val content = "첫 번째 댓글입니다"

            // When
            val response = commentService.createComment(
                userId = user.id.value,
                postId = post.id.value,
                content = content
            )

            // Then
            assertNotNull(response)
            assertEquals(content, response.content)
            assertEquals(user.id.value, response.author.id)
        }
    }

    @Test
    fun `댓글 작성 시 내용을 변형 없이 원문 그대로 저장한다`() {
        runBlocking {
            // Given: 모든 클라이언트가 네이티브(Compose Text) 렌더라 HTML 실행 컨텍스트가 없어
            // 저장 시점 살균을 하지 않는다. 과거 태그/핸들러 제거가 "<슬램덩크>"·"utm_content=" 같은
            // 정상 텍스트를 손상시켰던 회귀를 막는 테스트. (XSS 방어는 향후 HTML 출력 계층에서 처리)
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val content = "<슬램덩크> 봤어요 utm_content=abc"

            // When
            val response = commentService.createComment(
                userId = user.id.value,
                postId = post.id.value,
                content = content
            )

            // Then: 각괄호 표기·on 접두 파라미터가 삭제되지 않고 원문 그대로 보존된다(앞뒤 공백만 trim)
            assertNotNull(response)
            assertEquals(content, response.content)
        }
    }

    @Test
    fun `대댓글 작성 테스트`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            val post = TestFixtures.createTestPost(userId = user1.id.value)
            val parentComment = TestFixtures.createTestComment(
                userId = user1.id.value,
                postId = post.id.value,
                content = "원본 댓글"
            )

            // When
            val response = commentService.createComment(
                userId = user2.id.value,
                postId = post.id.value,
                content = "대댓글입니다",
                parentCommentId = parentComment.id.value
            )

            // Then
            assertNotNull(response)
            assertEquals("대댓글입니다", response.content)
            assertEquals(parentComment.id.value, response.parentCommentId)

            // 대댓글 알림 전송 확인
            coVerify {
                notificationService.sendReplyNotification(
                    commentOwnerId = user1.id.value,
                    replierId = user2.id.value,
                    postId = post.id.value,
                    commentId = any(),
                    parentCommentId = parentComment.id.value
                )
            }
        }
    }

    @Test
    fun `존재하지 않는 포스트에 댓글 작성 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val nonExistentPostId = 99999

            // When & Then
            assertFailsWith<PostNotFoundException> {
                commentService.createComment(
                    userId = user.id.value,
                    postId = nonExistentPostId,
                    content = "댓글 내용"
                )
            }
        }
    }

    @Test
    fun `존재하지 않는 부모 댓글에 대댓글 작성 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val nonExistentCommentId = 99999

            // When & Then
            assertFailsWith<CommentNotFoundException> {
                commentService.createComment(
                    userId = user.id.value,
                    postId = post.id.value,
                    content = "대댓글",
                    parentCommentId = nonExistentCommentId
                )
            }
        }
    }

    @Test
    fun `빈 댓글 작성 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When & Then
            assertFailsWith<InvalidCommentContentException> {
                commentService.createComment(
                    userId = user.id.value,
                    postId = post.id.value,
                    content = ""
                )
            }
        }
    }

    @Test
    fun `댓글 최대 길이 초과 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val tooLongContent = "a".repeat(10001) // 10000자 초과

            // When & Then
            assertFailsWith<InvalidCommentContentException> {
                commentService.createComment(
                    userId = user.id.value,
                    postId = post.id.value,
                    content = tooLongContent
                )
            }
        }
    }

    // ===== 댓글 목록 조회 테스트 (3개) =====

    @Test
    fun `포스트 댓글 목록 조회 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // 3개의 댓글 생성
            repeat(3) { i ->
                TestFixtures.createTestComment(
                    userId = user.id.value,
                    postId = post.id.value,
                    content = "댓글 ${i + 1}"
                )
            }

            // When
            val response = commentService.getPostComments(
                postId = post.id.value,
                currentUserId = user.id.value,
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
    fun `댓글 없는 포스트 조회 시 빈 목록 반환`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When
            val response = commentService.getPostComments(
                postId = post.id.value,
                currentUserId = user.id.value
            )

            // Then
            assertNotNull(response)
            assertEquals(0, response.items.size)
            assertEquals(0, response.totalCount)
        }
    }

    @Test
    fun `대댓글 목록 조회 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val parentComment = TestFixtures.createTestComment(
                userId = user.id.value,
                postId = post.id.value,
                content = "부모 댓글"
            )

            // 2개의 대댓글 생성
            repeat(2) { i ->
                TestFixtures.createTestComment(
                    userId = user.id.value,
                    postId = post.id.value,
                    content = "대댓글 ${i + 1}",
                    parentCommentId = parentComment.id.value
                )
            }

            // When
            val response = commentService.getCommentReplies(
                commentId = parentComment.id.value,
                currentUserId = user.id.value
            )

            // Then
            assertNotNull(response)
            assertEquals(2, response.items.size)
        }
    }

    // ===== 댓글 수정 테스트 (4개) =====

    @Test
    fun `댓글 수정 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val comment = TestFixtures.createTestComment(
                userId = user.id.value,
                postId = post.id.value,
                content = "원본 댓글"
            )
            val newContent = "수정된 댓글"

            // When
            val response = commentService.updateComment(
                commentId = comment.id.value,
                userId = user.id.value,
                content = newContent
            )

            // Then
            assertNotNull(response)
            assertEquals(newContent, response.content)
        }
    }

    @Test
    fun `댓글 수정 시 내용을 변형 없이 원문 그대로 저장한다`() {
        runBlocking {
            // Given: 작성과 동일 계약 — 저장 시점 살균 없이 원문 보존(네이티브 렌더)
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val comment = TestFixtures.createTestComment(
                userId = user.id.value,
                postId = post.id.value,
                content = "원본 댓글"
            )
            val content = "가격 <미정> onload 문의드려요"

            // When
            val response = commentService.updateComment(
                commentId = comment.id.value,
                userId = user.id.value,
                content = content
            )

            // Then: "<미정>"(태그 오인)·"onload"(핸들러 오탐)가 삭제되지 않고 원문 그대로 보존된다
            assertNotNull(response)
            assertEquals(content, response.content)
        }
    }

    @Test
    fun `다른 사용자의 댓글 수정 실패`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            val post = TestFixtures.createTestPost(userId = user1.id.value)
            val comment = TestFixtures.createTestComment(
                userId = user1.id.value,
                postId = post.id.value,
                content = "user1의 댓글"
            )

            // When & Then - user2가 user1의 댓글 수정 시도
            assertFailsWith<PermissionDeniedException> {
                commentService.updateComment(
                    commentId = comment.id.value,
                    userId = user2.id.value,
                    content = "수정 시도"
                )
            }
        }
    }

    @Test
    fun `존재하지 않는 댓글 수정 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val nonExistentCommentId = 99999

            // When & Then
            assertFailsWith<CommentNotFoundException> {
                commentService.updateComment(
                    commentId = nonExistentCommentId,
                    userId = user.id.value,
                    content = "수정 내용"
                )
            }
        }
    }

    // ===== 댓글 삭제 테스트 (3개) =====

    @Test
    fun `댓글 삭제 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val comment = TestFixtures.createTestComment(
                userId = user.id.value,
                postId = post.id.value,
                content = "삭제할 댓글"
            )

            // When
            commentService.deleteComment(
                commentId = comment.id.value,
                userId = user.id.value
            )

            // Then - 삭제 확인
            assertFailsWith<CommentNotFoundException> {
                commentService.updateComment(
                    commentId = comment.id.value,
                    userId = user.id.value,
                    content = "수정 시도"
                )
            }
        }
    }

    @Test
    fun `다른 사용자의 댓글 삭제 실패`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            val post = TestFixtures.createTestPost(userId = user1.id.value)
            val comment = TestFixtures.createTestComment(
                userId = user1.id.value,
                postId = post.id.value,
                content = "user1의 댓글"
            )

            // When & Then - user2가 user1의 댓글 삭제 시도
            assertFailsWith<PermissionDeniedException> {
                commentService.deleteComment(
                    commentId = comment.id.value,
                    userId = user2.id.value
                )
            }
        }
    }

    @Test
    fun `존재하지 않는 댓글 삭제 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val nonExistentCommentId = 99999

            // When & Then
            assertFailsWith<CommentNotFoundException> {
                commentService.deleteComment(
                    commentId = nonExistentCommentId,
                    userId = user.id.value
                )
            }
        }
    }

    // ===== 대댓글 테스트 (2개) =====

    @Test
    fun `다른 포스트의 댓글을 부모로 대댓글 작성 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post1 = TestFixtures.createTestPost(userId = user.id.value, content = "포스트 1")
            val post2 = TestFixtures.createTestPost(userId = user.id.value, content = "포스트 2")
            val comment1 = TestFixtures.createTestComment(
                userId = user.id.value,
                postId = post1.id.value
            )

            // When & Then - post2에 post1의 댓글을 부모로 지정
            assertFailsWith<InvalidInputException> {
                commentService.createComment(
                    userId = user.id.value,
                    postId = post2.id.value,
                    content = "대댓글",
                    parentCommentId = comment1.id.value
                )
            }
        }
    }

    @Test
    fun `댓글 알림 전송 확인`() {
        runBlocking {
            // Given
            val postOwner = TestFixtures.createTestUser(email = "owner@test.com", username = "owner")
            val commenter = TestFixtures.createTestUser(email = "commenter@test.com", username = "commenter")
            val post = TestFixtures.createTestPost(userId = postOwner.id.value)

            // When
            commentService.createComment(
                userId = commenter.id.value,
                postId = post.id.value,
                content = "댓글입니다"
            )

            // Then - 알림 전송 확인
            coVerify {
                notificationService.sendCommentNotification(
                    postOwnerId = postOwner.id.value,
                    commenterId = commenter.id.value,
                    postId = post.id.value,
                    commentId = any()
                )
            }
        }
    }
}
