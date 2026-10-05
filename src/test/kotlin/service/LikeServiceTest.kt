package com.ninezero.service

import com.ninezero.core.common.config.LikeType
import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.BookmarkRepositoryImpl
import com.ninezero.features.social.data.CommentRepositoryImpl
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.LikeRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.domain.LikeService
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coEvery
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
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LikeServiceTest {

    private lateinit var likeService: LikeService
    private lateinit var notificationService: NotificationService
    private lateinit var postService: PostService
    private lateinit var blockedUserRepository: BlockedUserRepositoryImpl
    private lateinit var followRepository: FollowRepositoryImpl
    private lateinit var cacheService: CacheService

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

            postService = mockk(relaxed = true)
            followRepository = FollowRepositoryImpl()
            blockedUserRepository = BlockedUserRepositoryImpl()
            cacheService = mockk(relaxed = true)

            // Service 초기화
            likeService = LikeService(
                likeRepository = LikeRepositoryImpl(),
                postRepository = PostRepositoryImpl(),
                commentRepository = CommentRepositoryImpl(),
                userRepository = UserRepositoryImpl(),
                notificationService = notificationService,
                bookmarkRepository = BookmarkRepositoryImpl(),
                postService = postService,
                followRepository = followRepository,
                blockedUserRepository = blockedUserRepository,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
                cacheService = cacheService
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()
            clearMocks(postService)  // 접근 판정 스텁이 다음 테스트로 새지 않도록
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    // ===== 포스트 좋아요 토글 테스트 =====

    @Test
    fun `여러 사용자가 포스트 좋아요 추가`() {
        runBlocking {
            // Given
            val users = TestFixtures.createTestUsers(count = 3)
            val post = TestFixtures.createTestPost(userId = users[0].id.value)

            // When - 3명이 좋아요
            val responses = users.map { user ->
                likeService.togglePostLike(user.id.value, post.id.value)
            }

            // Then
            assertEquals(3, responses.last().likeCount)

            // 한 명이 취소하면 줄어듦
            val cancelled = likeService.togglePostLike(users[2].id.value, post.id.value)
            assertTrue(!cancelled.isLiked)
            assertEquals(2, cancelled.likeCount)
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
    fun `비밀글 비참여자와 차단 관계 사용자는 포스트를 좋아요할 수 없다`() {
        runBlocking {
            val (author, outsider, blocked) = TestFixtures.createTestUsers(count = 3)
            val secretPost = TestFixtures.createTestPost(userId = author.id.value)
            val post = TestFixtures.createTestPost(userId = author.id.value)
            query { PostDao.findById(secretPost.id.value)!!.isSecret = true }
            coEvery { postService.checkPostAccess(outsider.id.value, any()) } returns false
            query { blockedUserRepository.createBlock(author.id.value, blocked.id.value) }

            assertFailsWith<PostNotFoundException> { likeService.togglePostLike(outsider.id.value, secretPost.id.value) }
            assertFailsWith<UserBlockedException> { likeService.togglePostLike(blocked.id.value, post.id.value) }
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

    // ===== 댓글 좋아요 토글 테스트 =====

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
            val responses = users.map { user ->
                likeService.toggleCommentLike(user.id.value, comment.id.value)
            }

            // Then - 좋아요 수 확인
            assertEquals(5, responses.last().likeCount)

            // 한 명이 취소하면 줄어듦
            val cancelled = likeService.toggleCommentLike(users[4].id.value, comment.id.value)
            assertTrue(!cancelled.isLiked)
            assertEquals(4, cancelled.likeCount)
        }
    }

    @Test
    fun `차단 관계에서는 댓글 좋아요 알림을 보내지 않는다`() {
        runBlocking {
            // Given
            clearMocks(notificationService, answers = false)
            val (commentOwner, liker) = TestFixtures.createTestUsers(count = 2)
            val post = TestFixtures.createTestPost(userId = commentOwner.id.value)
            val comment = TestFixtures.createTestComment(userId = commentOwner.id.value, postId = post.id.value)
            query { blockedUserRepository.createBlock(commentOwner.id.value, liker.id.value) }

            // When
            likeService.toggleCommentLike(liker.id.value, comment.id.value)

            // Then
            coVerify(exactly = 0) { notificationService.sendLikeCommentNotification(any(), any(), any(), any()) }
        }
    }

    // ===== 좋아요한 사용자 목록 조회 테스트 =====

    @Test
    fun `차단 관계 사용자는 좋아요한 사용자 목록에서 빠진다`() {
        runBlocking {
            // Given
            val (viewer, liker, blockedByViewer, blockerOfViewer) = TestFixtures.createTestUsers(count = 4)
            val post = TestFixtures.createTestPost(userId = viewer.id.value)
            listOf(liker, blockedByViewer, blockerOfViewer).forEach { user ->
                likeService.togglePostLike(user.id.value, post.id.value)
            }
            query {
                blockedUserRepository.createBlock(viewer.id.value, blockedByViewer.id.value)
                blockedUserRepository.createBlock(blockerOfViewer.id.value, viewer.id.value)
            }

            // When
            val response = likeService.getPostLikedUsers(postId = post.id.value, viewerId = viewer.id.value)

            // Then - 양방향 차단 모두 목록과 총개수에서 제외
            assertEquals(1, response.totalCount)
            assertEquals(listOf(liker.id.value), response.items.map { it.user.id })
        }
    }

    @Test
    fun `비밀글에 접근할 수 없으면 좋아요한 사용자 목록 조회 실패`() {
        runBlocking {
            // Given
            val (author, viewer) = TestFixtures.createTestUsers(count = 2)
            val post = TestFixtures.createTestPost(userId = author.id.value)
            query { PostDao.findById(post.id.value)!!.isSecret = true }
            coEvery { postService.checkPostAccess(viewer.id.value, any()) } returns false

            // When & Then
            assertFailsWith<PostNotFoundException> {
                likeService.getPostLikedUsers(postId = post.id.value, viewerId = viewer.id.value)
            }
        }
    }

    @Test
    fun `삭제된 비밀글의 댓글은 비참여자가 좋아요하거나 좋아요한 사용자를 볼 수 없다`() {
        runBlocking {
            // Given
            val (author, viewer) = TestFixtures.createTestUsers(count = 2)
            val post = TestFixtures.createTestPost(userId = author.id.value)
            val comment = TestFixtures.createTestComment(userId = author.id.value, postId = post.id.value)
            query { PostDao.findById(post.id.value)!!.apply { isSecret = true; isActive = false } }
            coEvery { postService.isSecretPostHiddenFrom(post.id.value, viewer.id.value) } returns true

            // When & Then
            assertFailsWith<CommentNotFoundException> {
                likeService.toggleCommentLike(currentUserId = viewer.id.value, commentId = comment.id.value)
            }
            assertFailsWith<CommentNotFoundException> {
                likeService.getCommentLikedUsers(commentId = comment.id.value, viewerId = viewer.id.value)
            }
        }
    }

    // ===== 사용자 좋아요 목록 테스트 =====

    @Test
    fun `차단 관계 작성자의 포스트와 댓글은 좋아요 목록에서 빠진다`() {
        runBlocking {
            // Given
            val (user, author, blockedAuthor) = TestFixtures.createTestUsers(count = 3)
            val visiblePost = TestFixtures.createTestPost(userId = author.id.value)
            val blockedPost = TestFixtures.createTestPost(userId = blockedAuthor.id.value)
            val blockedComment = TestFixtures.createTestComment(userId = blockedAuthor.id.value, postId = visiblePost.id.value)

            likeService.togglePostLike(user.id.value, visiblePost.id.value)
            likeService.togglePostLike(user.id.value, blockedPost.id.value)
            likeService.toggleCommentLike(user.id.value, blockedComment.id.value)
            query { blockedUserRepository.createBlock(user.id.value, blockedAuthor.id.value) }

            // When
            val response = likeService.getUserLikedItems(
                currentUserId = user.id.value,
                limit = 10
            )

            // Then
            assertEquals(listOf(visiblePost.id.value), response.items.map { it.targetId })
            assertEquals(false, response.hasNext)
        }
    }

    @Test
    fun `좋아요한 댓글의 작성자에도 팔로우 여부가 담긴다`() {
        runBlocking {
            // Given
            val (user, author) = TestFixtures.createTestUsers(count = 2)
            val post = TestFixtures.createTestPost(userId = author.id.value)
            val comment = TestFixtures.createTestComment(userId = author.id.value, postId = post.id.value)
            query { followRepository.createFollow(user.id.value, author.id.value) }
            likeService.toggleCommentLike(user.id.value, comment.id.value)

            // When
            val response = likeService.getUserLikedItems(
                currentUserId = user.id.value,
                limit = 10,
                targetType = LikeType.COMMENT
            )

            // Then
            assertEquals(true, response.items.single().comment?.author?.isFollowing)
        }
    }

    @Test
    fun `삭제·블라인드된 포스트와 댓글은 좋아요 목록에서 빠진다`() {
        runBlocking {
            val (user, author) = TestFixtures.createTestUsers(count = 2)
            val visiblePost = TestFixtures.createTestPost(userId = author.id.value)
            val removedPost = TestFixtures.createTestPost(userId = author.id.value)
            val removedComment = TestFixtures.createTestComment(userId = author.id.value, postId = visiblePost.id.value)

            likeService.togglePostLike(user.id.value, visiblePost.id.value)
            likeService.togglePostLike(user.id.value, removedPost.id.value)
            likeService.toggleCommentLike(user.id.value, removedComment.id.value)
            query {
                PostDao.findById(removedPost.id.value)!!.isActive = false
                CommentDao.findById(removedComment.id.value)!!.isActive = false
            }

            val response = likeService.getUserLikedItems(currentUserId = user.id.value, limit = 10)

            assertEquals(listOf(visiblePost.id.value), response.items.map { it.targetId })
            assertEquals(false, response.hasNext)
        }
    }

    @Test
    fun `포스트 좋아요 토글은 포스트 상세 캐시를 지운다`() {
        runBlocking {
            clearMocks(cacheService)
            val (user, author) = TestFixtures.createTestUsers(count = 2)
            val post = TestFixtures.createTestPost(userId = author.id.value)

            likeService.togglePostLike(user.id.value, post.id.value)

            coVerify { cacheService.deletePattern(CacheKeys.Patterns.post(post.id.value)) }
        }
    }
}
