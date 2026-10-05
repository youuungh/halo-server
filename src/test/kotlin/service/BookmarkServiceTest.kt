package com.ninezero.service

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.exception.CommentNotFoundException
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.exception.UserBlockedException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.features.social.data.BookmarkRepositoryImpl
import com.ninezero.features.social.data.CommentRepositoryImpl
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.LikeRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.domain.BookmarkService
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookmarkServiceTest {

    private lateinit var bookmarkService: BookmarkService
    private lateinit var bookmarkRepository: BookmarkRepositoryImpl
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var commentRepository: CommentRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var likeRepository: LikeRepositoryImpl
    private lateinit var followRepository: FollowRepositoryImpl
    private lateinit var blockedUserRepository: BlockedUserRepositoryImpl
    private lateinit var postService: PostService
    private lateinit var cacheService: CacheService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            cacheService = mockk(relaxed = true)
            postService = mockk(relaxed = true)
            bookmarkRepository = BookmarkRepositoryImpl()
            postRepository = PostRepositoryImpl()
            commentRepository = CommentRepositoryImpl()
            userRepository = UserRepositoryImpl()
            likeRepository = LikeRepositoryImpl()
            followRepository = FollowRepositoryImpl()
            blockedUserRepository = BlockedUserRepositoryImpl()

            bookmarkService = BookmarkService(
                bookmarkRepository = bookmarkRepository,
                postRepository = postRepository,
                commentRepository = commentRepository,
                userRepository = userRepository,
                likeRepository = likeRepository,
                postService = postService,
                followRepository = followRepository,
                blockedUserRepository = blockedUserRepository,
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

    // ===== 북마크 토글 테스트 =====

    @Test
    fun `포스트 북마크 토글은 포스트 상세 캐시를 지운다`() {
        runBlocking {
            clearMocks(cacheService)
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            bookmarkService.togglePostBookmark(user.id.value, post.id.value)

            coVerify { cacheService.deletePattern(CacheKeys.Patterns.post(post.id.value)) }
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

    @Test
    fun `비밀글 비참여자와 차단 관계 사용자는 포스트를 북마크할 수 없다`() {
        runBlocking {
            val (author, outsider, blocked) = TestFixtures.createTestUsers(count = 3)
            val secretPost = TestFixtures.createTestPost(userId = author.id.value)
            val post = TestFixtures.createTestPost(userId = author.id.value)
            query { PostDao.findById(secretPost.id.value)!!.isSecret = true }
            coEvery { postService.checkPostAccess(outsider.id.value, any()) } returns false
            query { blockedUserRepository.createBlock(author.id.value, blocked.id.value) }

            assertFailsWith<PostNotFoundException> { bookmarkService.togglePostBookmark(outsider.id.value, secretPost.id.value) }
            assertFailsWith<UserBlockedException> { bookmarkService.togglePostBookmark(blocked.id.value, post.id.value) }
        }
    }

    @Test
    fun `삭제된 비밀글의 댓글은 비참여자가 북마크할 수 없다`() {
        runBlocking {
            // Given
            val (author, viewer) = TestFixtures.createTestUsers(count = 2)
            val post = TestFixtures.createTestPost(userId = author.id.value)
            val comment = TestFixtures.createTestComment(userId = author.id.value, postId = post.id.value)
            query { PostDao.findById(post.id.value)!!.apply { isSecret = true; isActive = false } }
            coEvery { postService.isSecretPostHiddenFrom(post.id.value, viewer.id.value) } returns true

            // When & Then
            assertFailsWith<CommentNotFoundException> {
                bookmarkService.toggleCommentBookmark(userId = viewer.id.value, commentId = comment.id.value)
            }
        }
    }

    // ===== 북마크 목록 조회 테스트 =====

    @Test
    fun `저장한 댓글의 작성자에도 팔로우 여부가 담긴다`() {
        runBlocking {
            // Given
            val (user, author) = TestFixtures.createTestUsers(count = 2)
            val post = TestFixtures.createTestPost(userId = author.id.value)
            val comment = TestFixtures.createTestComment(userId = author.id.value, postId = post.id.value)
            query { followRepository.createFollow(user.id.value, author.id.value) }
            bookmarkService.toggleCommentBookmark(user.id.value, comment.id.value)

            // When
            val response = bookmarkService.getUserBookmarks(
                userId = user.id.value,
                limit = 10,
                targetType = BookmarkTargetType.COMMENT
            )

            // Then
            assertEquals(true, response.items.single().comment?.author?.isFollowing)
        }
    }

    @Test
    fun `삭제·블라인드된 포스트와 댓글의 북마크는 목록에서 빠진다`() {
        runBlocking {
            val (user, author) = TestFixtures.createTestUsers(count = 2)
            val visiblePost = TestFixtures.createTestPost(userId = author.id.value)
            val deletedPost = TestFixtures.createTestPost(userId = author.id.value)
            val blindedPost = TestFixtures.createTestPost(userId = author.id.value)
            val deletedComment = TestFixtures.createTestComment(userId = author.id.value, postId = visiblePost.id.value)

            bookmarkService.togglePostBookmark(user.id.value, visiblePost.id.value)
            bookmarkService.togglePostBookmark(user.id.value, deletedPost.id.value)
            bookmarkService.togglePostBookmark(user.id.value, blindedPost.id.value)
            bookmarkService.toggleCommentBookmark(user.id.value, deletedComment.id.value)
            query {
                PostDao.findById(deletedPost.id.value)!!.isActive = false
                PostDao.findById(blindedPost.id.value)!!.apply { isActive = false; isBlinded = true }
                CommentDao.findById(deletedComment.id.value)!!.isActive = false
            }

            val response = bookmarkService.getUserBookmarks(userId = user.id.value, limit = 10)

            assertEquals(listOf(visiblePost.id.value), response.items.map { it.targetId })
            assertEquals(false, response.hasNext)
        }
    }

    @Test
    fun `차단 관계 작성자의 포스트와 댓글은 북마크 목록에서 빠진다`() {
        runBlocking {
            // Given
            val (user, author, blockedAuthor) = TestFixtures.createTestUsers(count = 3)
            val visiblePost = TestFixtures.createTestPost(userId = author.id.value)
            val blockedPost = TestFixtures.createTestPost(userId = blockedAuthor.id.value)
            val blockedComment = TestFixtures.createTestComment(userId = blockedAuthor.id.value, postId = visiblePost.id.value)

            bookmarkService.togglePostBookmark(user.id.value, visiblePost.id.value)
            bookmarkService.togglePostBookmark(user.id.value, blockedPost.id.value)
            bookmarkService.toggleCommentBookmark(user.id.value, blockedComment.id.value)
            // 작성자 쪽에서 차단한 경우도 제외
            query { blockedUserRepository.createBlock(blockedAuthor.id.value, user.id.value) }

            // When
            val response = bookmarkService.getUserBookmarks(
                userId = user.id.value,
                limit = 10
            )

            // Then
            assertEquals(listOf(visiblePost.id.value), response.items.map { it.targetId })
            assertEquals(false, response.hasNext)
        }
    }
}
