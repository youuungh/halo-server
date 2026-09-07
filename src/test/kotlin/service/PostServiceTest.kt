package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.CreatorOnlyException
import com.ninezero.core.common.exception.InvalidPostContentException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.PermissionDeniedException
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.BookmarkRepositoryImpl
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.LikeRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.tag.data.TagRepositoryImpl
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.social.presentation.models.request.PostRequest
import com.ninezero.features.social.presentation.models.request.UpdatePostRequest
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * PostService 테스트
 *
 * 테스트 케이스: 12개
 * - 포스트 생성: 4개
 * - 포스트 조회: 3개
 * - 포스트 수정: 3개
 * - 포스트 삭제: 2개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostServiceTest {

    private lateinit var postService: PostService
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var likeRepository: LikeRepositoryImpl
    private lateinit var bookmarkRepository: BookmarkRepositoryImpl
    private lateinit var followRepository: FollowRepositoryImpl
    private lateinit var tagRepository: TagRepositoryImpl
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var planRepository: SubscriptionPlanRepositoryImpl
    private lateinit var blockedUserRepository: BlockedUserRepositoryImpl

    // Mock Services
    private lateinit var notificationService: NotificationService
    private lateinit var fileUploadService: FileUploadService
    private lateinit var imageProcessingService: ImageProcessingService
    private lateinit var videoProcessingService: VideoProcessingService
    private lateinit var cacheService: CacheService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            // Repository 초기화
            postRepository = PostRepositoryImpl()
            userRepository = UserRepositoryImpl()
            likeRepository = LikeRepositoryImpl()
            bookmarkRepository = BookmarkRepositoryImpl()
            followRepository = FollowRepositoryImpl()
            tagRepository = TagRepositoryImpl()
            subscriptionRepository = SubscriptionRepositoryImpl()
            planRepository = SubscriptionPlanRepositoryImpl()
            blockedUserRepository = BlockedUserRepositoryImpl()

            // Mock Services
            notificationService = mockk(relaxed = true)
            fileUploadService = mockk(relaxed = true)
            imageProcessingService = mockk(relaxed = true)
            videoProcessingService = mockk(relaxed = true)
            cacheService = mockk(relaxed = true)

            // Mock 설정 (suspend 함수는 coJustRun 사용)
            coJustRun { fileUploadService.deleteFileIfSupabase(any()) }
            coEvery { imageProcessingService.validateImage(any(), any()) } returns true
            coEvery { imageProcessingService.validateVideo(any(), any()) } returns true
            coEvery { imageProcessingService.getFileExtension(any()) } returns "jpg"
            coEvery { cacheService.get<Any>(any(), any()) } returns null

            postService = PostService(
                userRepository = userRepository,
                postRepository = postRepository,
                likeRepository = likeRepository,
                bookmarkRepository = bookmarkRepository,
                followRepository = followRepository,
                tagRepository = tagRepository,
                subscriptionRepository = subscriptionRepository,
                planRepository = planRepository,
                blockedUserRepository = blockedUserRepository,
                notificationService = notificationService,
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
                videoProcessingService = videoProcessingService,
                cacheService = cacheService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined)
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

    // ===== 포스트 생성 테스트 (4개) =====

    @Test
    fun `포스트 생성 성공 - 일반 사용자`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            val request = PostRequest(
                content = "안녕하세요! 첫 번째 포스트입니다.",
                postType = PostType.TEXT,
                
                productId = null,
                requiredTier = SubscriptionPlanTier.FREE,
                tagIds = null,
                sectionTagId = null
            )

            // When
            val response = postService.createPost(user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals("안녕하세요! 첫 번째 포스트입니다.", response.content)
            assertEquals(PostType.TEXT, response.postType)
        }
    }

    @Test
    fun `포스트 생성 성공 - 크리에이터`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()

            val request = PostRequest(
                content = "크리에이터 포스트입니다!",
                postType = PostType.TEXT,
                
                productId = null,
                requiredTier = SubscriptionPlanTier.FREE,
                tagIds = null,
                sectionTagId = null
            )

            // When
            val response = postService.createPost(creator.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals("크리에이터 포스트입니다!", response.content)
        }
    }

    @Test
    fun `포스트 생성 실패 - 빈 내용`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            val request = PostRequest(
                content = "",
                postType = PostType.TEXT,
                
                productId = null,
                requiredTier = SubscriptionPlanTier.FREE,
                tagIds = null,
                sectionTagId = null
            )

            // When & Then
            assertFailsWith<InvalidPostContentException> {
                postService.createPost(user.id.value, request)
            }
        }
    }

    @Test
    fun `포스트 생성 실패 - 일반 사용자가 유료 티어 포스트 작성 시도`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            val request = PostRequest(
                content = "유료 전용 컨텐츠입니다.",
                postType = PostType.TEXT,
                
                productId = null,
                requiredTier = SubscriptionPlanTier.TIER1,
                tagIds = null,
                sectionTagId = null
            )

            // When & Then
            assertFailsWith<CreatorOnlyException> {
                postService.createPost(user.id.value, request)
            }
        }
    }

    // ===== 포스트 조회 테스트 (3개) =====

    @Test
    fun `포스트 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When
            val response = postService.getPostById(post.id.value, user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(post.id.value, response.id)
        }
    }

    @Test
    fun `존재하지 않는 포스트 조회 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When & Then
            assertFailsWith<PostNotFoundException> {
                postService.getPostById(99999, user.id.value)
            }
        }
    }

    @Test
    fun `사용자 포스트 목록 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // 3개 포스트 생성
            TestFixtures.createTestPost(userId = user.id.value)
            TestFixtures.createTestPost(userId = user.id.value)
            TestFixtures.createTestPost(userId = user.id.value)

            // When
            val response = postService.getUserPosts(
                targetUserId = user.id.value,
                currentUserId = user.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(3, response.items.size)
        }
    }

    // ===== 포스트 수정 테스트 (3개) =====

    @Test
    fun `포스트 수정 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            val request = UpdatePostRequest(
                content = "수정된 내용입니다.",
                
                tagIds = null,
                sectionTagId = null
            )

            // When
            val response = postService.updatePost(post.id.value, user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals("수정된 내용입니다.", response.content)
        }
    }

    @Test
    fun `포스트 수정 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val otherUser = TestFixtures.createTestUser(email = "other@test.com", username = "other")
            val post = TestFixtures.createTestPost(userId = user.id.value)

            val request = UpdatePostRequest(
                content = "다른 사용자가 수정 시도",
                
                tagIds = null,
                sectionTagId = null
            )

            // When & Then
            assertFailsWith<PermissionDeniedException> {
                postService.updatePost(post.id.value, otherUser.id.value, request)
            }
        }
    }

    @Test
    fun `포스트 수정 실패 - 빈 내용`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            val request = UpdatePostRequest(
                content = "",
                
                tagIds = null,
                sectionTagId = null
            )

            // When & Then
            assertFailsWith<InvalidPostContentException> {
                postService.updatePost(post.id.value, user.id.value, request)
            }
        }
    }

    // ===== 포스트 삭제 테스트 (2개) =====

    @Test
    fun `포스트 삭제 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When
            postService.deletePost(post.id.value, user.id.value)

            // Then - 삭제 확인
            assertFailsWith<PostNotFoundException> {
                postService.getPostById(post.id.value, user.id.value)
            }
        }
    }

    @Test
    fun `포스트 삭제 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val otherUser = TestFixtures.createTestUser(email = "other@test.com", username = "other")
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When & Then
            assertFailsWith<PermissionDeniedException> {
                postService.deletePost(post.id.value, otherUser.id.value)
            }
        }
    }

    // ===== 크리에이터 해제 숨김 글 조회 =====

    @Test
    fun `포스트 조회 - 크리에이터 해제로 숨겨진 글은 유효하지 않은 게시물 404`() {
        runBlocking {
            // Given — 해제 teardown이 숨긴 글(status=HIDDEN)
            val creator = TestFixtures.createTestCreator()
            val post = TestFixtures.createTestPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                contextType = PostContextType.COMMUNITY
            )
            query { postRepository.hideCreatorPosts(creator.id.value) }

            // When & Then — 삭제와 구분되는 메시지
            val exception = assertFailsWith<NotFoundException> {
                postService.getPostById(post.id.value, null)
            }
            assertEquals("유효하지 않은 게시물입니다.", exception.message)

            // 일반 삭제 글은 기존 404 유지
            val deletedPost = TestFixtures.createTestPost(userId = creator.id.value)
            query { postRepository.deletePost(deletedPost.id.value) }
            assertFailsWith<PostNotFoundException> {
                postService.getPostById(deletedPost.id.value, null)
            }
        }
    }
}
