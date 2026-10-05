package com.ninezero.service

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.MediaDraftTargetType
import com.ninezero.core.common.config.ReportTargetType
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.CommentMediaDao
import com.ninezero.core.database.entities.social.CommentMediaTable
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.core.database.entities.upload.MediaDraftDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.*
import com.ninezero.features.social.domain.CommentService
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.social.domain.ReportService
import com.ninezero.features.upload.data.MediaDraftRepositoryImpl
import com.ninezero.features.upload.data.MediaUploadRepositoryImpl
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.upload.domain.MediaUploadService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommentServiceTest {

    private lateinit var commentService: CommentService
    private lateinit var notificationService: NotificationService
    private lateinit var fileUploadService: FileUploadService
    private lateinit var imageProcessingService: ImageProcessingService
    private lateinit var videoProcessingService: VideoProcessingService
    private lateinit var postService: PostService
    private val cacheService: CacheService = mockk(relaxed = true)

    private val mp4Head = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D, 0, 0, 0, 0)

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
                notificationService.sendMentionNotification(any(), any(), any(), any(), any(), any())
            } returns null

            postService = mockk(relaxed = true)
            coEvery { postService.checkPostAccess(any(), any()) } returns true
            coEvery { postService.isSecretPostHiddenFrom(any(), any()) } returns false

            // Service 초기화
            fileUploadService = mockk(relaxed = true)
            imageProcessingService = mockk(relaxed = true)
            videoProcessingService = mockk(relaxed = true)

            // 직접 업로드 경로
            coEvery { fileUploadService.fileSize(any(), any()) } returns 1000L
            coEvery { fileUploadService.readHead(any(), any(), any()) } returns mp4Head
            coEvery { fileUploadService.downloadToTempFile(any(), any(), any()) } answers { File.createTempFile("test_src", ".tmp") }
            coEvery { fileUploadService.downloadToTempFile(any<String>()) } answers { File.createTempFile("test_src", ".tmp") }
            coEvery { imageProcessingService.validateImage(any(), any()) } returns true
            coEvery { videoProcessingService.optimizeForStreaming(any<File>()) } returns null
            coEvery { videoProcessingService.extractMetadata(any<File>()) } returns VideoProcessingService.VideoMetadata()
            coEvery { videoProcessingService.extractThumbnail(any<File>()) } returns null
            coEvery { videoProcessingService.transcodeForStreaming(any<File>()) } returns null

            val mediaUploadService = MediaUploadService(
                mediaUploadRepository = MediaUploadRepositoryImpl(),
                fileUploadService = fileUploadService,
                imageProcessingService = ImageProcessingService()
            )
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
                mediaDraftService = MediaDraftService(
                    mediaDraftRepository = MediaDraftRepositoryImpl(),
                    mediaUploadService = mediaUploadService,
                    fileUploadService = fileUploadService,
                    imageProcessingService = imageProcessingService,
                    videoProcessingService = videoProcessingService,
                    coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
                ),
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
                followRepository = mockk(relaxed = true),
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

    // ===== 댓글 작성 테스트 =====

    @Test
    fun `댓글 작성 시 내용을 변형 없이 원문 그대로 저장한다`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val content = "<슬램덩크> 봤어요 utm_content=abc"

            // When
            val response = commentService.createComment(
                userId = user.id.value,
                postId = post.id.value,
                content = content
            )

            // Then
            assertEquals(content, response.content)
            assertEquals(user.id.value, response.author.id)
        }
    }

    @Test
    fun `대댓글 작성`() {
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
    fun `댓글 최대 길이 초과 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val tooLongContent = "a".repeat(10001)  // 10000자 초과

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

    // ===== 댓글 목록 조회 테스트 =====

    @Test
    fun `비밀글 숨김 판정이면 댓글·답글·스레드를 비참여자에게 숨긴다`() {
        runBlocking {
            // Given - 댓글이 달린 비밀글이 비활성
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val comment = TestFixtures.createTestComment(userId = user.id.value, postId = post.id.value)
            TestFixtures.createTestComment(userId = user.id.value, postId = post.id.value, parentCommentId = comment.id.value)
            query { PostDao.findById(post.id.value)!!.apply { isSecret = true; isActive = false } }
            coEvery { postService.isSecretPostHiddenFrom(post.id.value, null) } returns true

            // When & Then - 비로그인 조회
            assertEquals(0, commentService.getPostComments(postId = post.id.value, currentUserId = null).totalCount)
            assertEquals(0, commentService.getCommentReplies(commentId = comment.id.value, currentUserId = null).totalCount)
            assertFailsWith<CommentNotFoundException> {
                commentService.getCommentThread(commentId = comment.id.value, currentUserId = null)
            }

            // 접근 가능한 사용자는 그대로 열람
            val visible = commentService.getPostComments(postId = post.id.value, currentUserId = user.id.value)
            assertEquals(2, visible.totalCount)
            assertEquals(1, visible.items.size)
        }
    }

    // ===== 댓글 수정 테스트 =====

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
            assertFailsWith<ForbiddenException> {
                commentService.updateComment(
                    commentId = comment.id.value,
                    userId = user2.id.value,
                    content = "수정 시도"
                )
            }
        }
    }

    // ===== 댓글 삭제 테스트 =====

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
            assertFailsWith<ForbiddenException> {
                commentService.deleteComment(
                    commentId = comment.id.value,
                    userId = user2.id.value
                )
            }
        }
    }

    @Test
    fun `답글이 남은 댓글은 삭제 자리로 남고 아래 답글이 모두 지워지면 위로 함께 사라진다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val root = commentService.createComment(user.id.value, post.id.value, "루트 댓글")
            val middle = commentService.createComment(user.id.value, post.id.value, "답글", parentCommentId = root.id)
            val leaf = commentService.createComment(user.id.value, post.id.value, "답글의 답글", parentCommentId = middle.id)
            suspend fun counts() = query {
                PostDao.findById(post.id.value)!!.commentCount to CommentDao.findById(root.id)!!.replyCount
            }

            commentService.deleteComment(root.id, user.id.value)
            commentService.deleteComment(middle.id, user.id.value)

            // 자리만 남고 내용은 비움, 답글은 그대로 이어짐
            val rootPlaceholder = commentService.getPostComments(post.id.value, user.id.value).items.single()
            assertTrue(rootPlaceholder.isDeleted)
            assertEquals("", rootPlaceholder.content)
            assertEquals(middle.id, rootPlaceholder.firstReply?.id)
            val middlePlaceholder = commentService.getCommentReplies(root.id, user.id.value).items.single()
            assertTrue(middlePlaceholder.isDeleted)
            assertEquals(leaf.id, middlePlaceholder.firstReply?.id)
            assertEquals(listOf(root.id, middle.id, leaf.id), commentService.getCommentThread(leaf.id, user.id.value).map { it.id })
            // 댓글 수는 지운 2개만, 답글 수는 자리가 남아 그대로
            assertEquals(1 to 1, counts())
            assertFailsWith<CommentNotFoundException> {
                commentService.createComment(user.id.value, post.id.value, "자리에 답글", parentCommentId = middle.id)
            }
            assertFailsWith<CommentNotFoundException> {
                commentService.updateComment(root.id, user.id.value, "자리 수정")
            }

            commentService.deleteComment(leaf.id, user.id.value)

            assertTrue(commentService.getPostComments(post.id.value, user.id.value).items.isEmpty())
            assertEquals(0 to 0, counts())
        }
    }

    @Test
    fun `블라인드된 내 댓글은 작성자가 삭제할 수 있고 댓글 수를 두 번 빼지 않는다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val comment = commentService.createComment(user.id.value, post.id.value, "블라인드될 댓글")
            val reportService = ReportService(
                ReportRepositoryImpl(),
                UserRepositoryImpl(),
                PostRepositoryImpl(),
                CommentRepositoryImpl(),
                mockk(relaxed = true)
            )
            reportService.blind(ReportTargetType.COMMENT, comment.id)

            commentService.deleteComment(comment.id, user.id.value)

            val (commentCount, isBlinded) = query {
                PostDao.findById(post.id.value)!!.commentCount to CommentDao.findById(comment.id)!!.isBlinded
            }
            assertEquals(0, commentCount)
            assertFalse(isBlinded)
        }
    }

    @Test
    fun `댓글을 쓰거나 지우면 댓글 수가 담긴 포스트 상세 캐시를 지운다`() {
        runBlocking {
            clearMocks(cacheService)
            val (author, commenter) = TestFixtures.createTestUsers(count = 2)
            val post = TestFixtures.createTestPost(userId = author.id.value)

            val comment = commentService.createComment(commenter.id.value, post.id.value, "댓글")
            coVerify(exactly = 1) { cacheService.deletePattern(CacheKeys.Patterns.post(post.id.value)) }

            commentService.deleteComment(comment.id, commenter.id.value)
            coVerify(exactly = 2) { cacheService.deletePattern(CacheKeys.Patterns.post(post.id.value)) }
        }
    }

    // ===== 대댓글 테스트 =====

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

            // Then
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

    @Test
    fun `대문자로 쓴 멘션도 소문자 username 사용자에게 알림`() {
        runBlocking {
            // Given
            val postOwner = TestFixtures.createTestUser(email = "owner@test.com", username = "owner")
            val commenter = TestFixtures.createTestUser(email = "commenter@test.com", username = "commenter")
            val minji = TestFixtures.createTestUser(email = "minji@test.com", username = "minji")
            val post = TestFixtures.createTestPost(userId = postOwner.id.value)

            // When
            commentService.createComment(
                userId = commenter.id.value,
                postId = post.id.value,
                content = "@Minji 이거 봐"
            )

            // Then
            coVerify(timeout = 2000) {
                notificationService.sendMentionNotification(
                    mentionedUserId = minji.id.value,
                    mentionerId = commenter.id.value,
                    postId = post.id.value,
                    commentId = any(),
                    content = any(),
                    canViewContent = true
                )
            }
        }
    }

    @Test
    fun `답글 알림을 받는 부모 댓글 작성자는 멘션해도 멘션 알림을 또 받지 않는다`() {
        runBlocking {
            clearMocks(notificationService, answers = false)
            val parentOwner = TestFixtures.createTestUser(email = "parentowner@test.com", username = "parentowner")
            val replier = TestFixtures.createTestUser(email = "replier@test.com", username = "replier")
            val other = TestFixtures.createTestUser(email = "other@test.com", username = "other")
            val post = TestFixtures.createTestPost(userId = parentOwner.id.value)
            val parent = TestFixtures.createTestComment(userId = parentOwner.id.value, postId = post.id.value)

            commentService.createComment(
                userId = replier.id.value,
                postId = post.id.value,
                content = "@parentowner @other 이거 봐",
                parentCommentId = parent.id.value
            )

            // other 알림이 나간 뒤면 같은 순회의 parentowner 판정도 끝남
            coVerify(timeout = 2000) {
                notificationService.sendMentionNotification(
                    mentionedUserId = other.id.value,
                    mentionerId = replier.id.value,
                    postId = post.id.value,
                    commentId = any(),
                    content = any(),
                    canViewContent = any()
                )
            }
            coVerify(exactly = 0) {
                notificationService.sendMentionNotification(
                    mentionedUserId = parentOwner.id.value,
                    mentionerId = any(),
                    postId = any(),
                    commentId = any(),
                    content = any(),
                    canViewContent = any()
                )
            }
        }
    }

    @Test
    fun `잠긴 글에서 멘션된 비참여자에게는 본문 없이 알린다`() {
        runBlocking {
            clearMocks(notificationService, answers = false)
            val postOwner = TestFixtures.createTestUser(email = "owner@test.com", username = "owner")
            val commenter = TestFixtures.createTestUser(email = "commenter@test.com", username = "commenter")
            val outsider = TestFixtures.createTestUser(email = "outsider@test.com", username = "outsider")
            val post = TestFixtures.createTestPost(userId = postOwner.id.value)
            coEvery { postService.checkPostAccess(outsider.id.value, any()) } returns false

            try {
                commentService.createComment(
                    userId = commenter.id.value,
                    postId = post.id.value,
                    content = "@outsider 잠긴 글 내용"
                )

                coVerify(timeout = 2000) {
                    notificationService.sendMentionNotification(
                        mentionedUserId = outsider.id.value,
                        mentionerId = commenter.id.value,
                        postId = post.id.value,
                        commentId = any(),
                        content = any(),
                        canViewContent = false
                    )
                }
            } finally {
                coEvery { postService.checkPostAccess(any(), any()) } returns true
            }
        }
    }

    // ===== 댓글 미디어 직접 업로드 테스트 =====

    @Test
    fun `댓글 작성 - 초안을 순서대로 미디어에 붙이고 초안은 지운다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val (first, second) = List(2) { TestFixtures.createTestMediaDraft(user.id.value, targetType = MediaDraftTargetType.COMMENT) }

            val response = commentService.createComment(
                userId = user.id.value,
                postId = post.id.value,
                content = "초안 댓글",
                mediaDraftIds = listOf(second.id.value, first.id.value)
            )

            assertEquals(listOf(second.url, first.url), response.mediaItems.map { it.url })
            assertEquals(2L, query { CommentMediaDao.find { CommentMediaTable.commentId eq response.id }.count() })
            assertEquals(0L, query { MediaDraftDao.all().count() })

            // 글용 초안은 댓글에 못 붙이고 댓글도 생기지 않음
            val postDraft = TestFixtures.createTestMediaDraft(user.id.value, targetType = MediaDraftTargetType.POST)
            assertFailsWith<NotFoundException> {
                commentService.createComment(user.id.value, post.id.value, "글용 초안", mediaDraftIds = listOf(postDraft.id.value))
            }
            assertEquals(1L, query { CommentDao.all().count() })
        }
    }

    @Test
    fun `댓글 작성 - 같은 요청 식별자는 새 댓글 없이 만든 댓글을 돌려준다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val draft = TestFixtures.createTestMediaDraft(user.id.value, targetType = MediaDraftTargetType.COMMENT)

            val first = commentService.createComment(user.id.value, post.id.value, "한 번만", mediaDraftIds = listOf(draft.id.value), clientRequestId = "pending-1")
            val again = commentService.createComment(user.id.value, post.id.value, "한 번만", mediaDraftIds = listOf(draft.id.value), clientRequestId = "pending-1")

            assertEquals(first.id, again.id)
            assertEquals(listOf(draft.url), again.mediaItems.map { it.url })
            assertEquals(1L, query { CommentDao.all().count() })
            assertEquals(1, query { PostDao.findById(post.id.value)!!.commentCount })
        }
    }
}
