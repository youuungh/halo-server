package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.InvalidPostContentException
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.features.social.data.BookmarkRepositoryImpl
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.LikeRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.domain.CommunityService
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.social.presentation.models.request.CommunityPostRequest
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * CommunityService 테스트
 *
 * 테스트 케이스: 12개
 * - 커뮤니티 글 작성: 4개
 * - 커뮤니티 글 수정: 2개
 * - 커뮤니티 글 삭제: 3개
 * - 커뮤니티 조회: 2개
 * - 공지 고정: 1개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityServiceTest {

    private lateinit var communityService: CommunityService
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var likeRepository: LikeRepositoryImpl
    private lateinit var bookmarkRepository: BookmarkRepositoryImpl
    private lateinit var followRepository: FollowRepositoryImpl

    // Mock Services
    private lateinit var fileUploadService: FileUploadService
    private lateinit var imageProcessingService: ImageProcessingService
    private lateinit var cacheService: CacheService
    private lateinit var subscriptionRepository: SubscriptionRepository
    private lateinit var planRepository: SubscriptionPlanRepository

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            postRepository = PostRepositoryImpl()
            userRepository = UserRepositoryImpl()
            likeRepository = LikeRepositoryImpl()
            bookmarkRepository = BookmarkRepositoryImpl()
            followRepository = FollowRepositoryImpl()

            // Mock Services
            fileUploadService = mockk(relaxed = true)
            imageProcessingService = mockk(relaxed = true)
            cacheService = mockk(relaxed = true)
            subscriptionRepository = mockk(relaxed = true)
            planRepository = mockk(relaxed = true)

            coJustRun { fileUploadService.deleteFileIfSupabase(any()) }
            coEvery { imageProcessingService.validateImage(any(), any()) } returns true
            coEvery { imageProcessingService.getFileExtension(any()) } returns "jpg"
            coEvery { cacheService.get<Any>(any(), any()) } returns null

            communityService = CommunityService(
                postRepository = postRepository,
                userRepository = userRepository,
                likeRepository = likeRepository,
                bookmarkRepository = bookmarkRepository,
                followRepository = followRepository,
                subscriptionRepository = subscriptionRepository,
                planRepository = planRepository,
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
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

    // ===== 커뮤니티 글 작성 테스트 (4개) =====

    @Test
    fun `커뮤니티 글 작성 성공 - 크리에이터 본인`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val request = CommunityPostRequest(
                content = "크리에이터 커뮤니티 글입니다",
            )

            // When
            val response = communityService.createCommunityPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                request = request
            )

            // Then
            assertNotNull(response)
            assertEquals("크리에이터 커뮤니티 글입니다", response.content)
            assertEquals(PostType.IMAGE, response.postType)
        }
    }

    @Test
    fun `커뮤니티 글 작성 성공 - 팔로워`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val follower = TestFixtures.createTestUser(email = "follower@test.com", username = "follower")

            // 팔로우
            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator.id.value)

            val request = CommunityPostRequest(
                content = "팔로워 커뮤니티 글입니다",
            )

            // When
            val response = communityService.createCommunityPost(
                userId = follower.id.value,
                creatorId = creator.id.value,
                request = request
            )

            // Then
            assertNotNull(response)
            assertEquals("팔로워 커뮤니티 글입니다", response.content)
        }
    }

    @Test
    fun `커뮤니티 글 작성 실패 - 팔로워 아님`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val nonFollower = TestFixtures.createTestUser(email = "user@test.com", username = "user")

            val request = CommunityPostRequest(
                content = "작성 시도",
            )

            // When & Then
            assertFailsWith<ForbiddenException> {
                communityService.createCommunityPost(
                    userId = nonFollower.id.value,
                    creatorId = creator.id.value,
                    request = request
                )
            }
        }
    }

    @Test
    fun `커뮤니티 글 작성 실패 - 빈 내용`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val request = CommunityPostRequest(
                content = "",
            )

            // When & Then
            assertFailsWith<InvalidPostContentException> {
                communityService.createCommunityPost(
                    userId = creator.id.value,
                    creatorId = creator.id.value,
                    request = request
                )
            }
        }
    }

    // ===== 커뮤니티 글 수정 테스트 (2개) =====

    @Test
    fun `커뮤니티 글 수정 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val post = communityService.createCommunityPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                request = CommunityPostRequest(content = "원본 글")
            )

            val updateRequest = CommunityPostRequest(
                content = "수정된 글",
            )

            // When
            val response = communityService.updateCommunityPost(
                postId = post.id,
                userId = creator.id.value,
                request = updateRequest
            )

            // Then
            assertNotNull(response)
            assertEquals("수정된 글", response.content)
        }
    }

    @Test
    fun `커뮤니티 글 수정 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val follower = TestFixtures.createTestUser(email = "follower@test.com", username = "follower")

            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator.id.value)

            val post = communityService.createCommunityPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                request = CommunityPostRequest(content = "크리에이터 글")
            )

            val updateRequest = CommunityPostRequest(
                content = "수정 시도",
            )

            // When & Then - 팔로워가 크리에이터 글 수정 시도
            assertFailsWith<ForbiddenException> {
                communityService.updateCommunityPost(
                    postId = post.id,
                    userId = follower.id.value,
                    request = updateRequest
                )
            }
        }
    }

    // ===== 커뮤니티 글 삭제 테스트 (3개) =====

    @Test
    fun `커뮤니티 글 삭제 성공 - 작성자`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val follower = TestFixtures.createTestUser(email = "follower@test.com", username = "follower")

            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator.id.value)

            val post = communityService.createCommunityPost(
                userId = follower.id.value,
                creatorId = creator.id.value,
                request = CommunityPostRequest(content = "팔로워 글")
            )

            // When
            communityService.deleteCommunityPost(post.id, follower.id.value)

            // Then - 삭제 확인 (조회 시 오류)
            assertFailsWith<PostNotFoundException> {
                communityService.updateCommunityPost(
                    postId = post.id,
                    userId = follower.id.value,
                    request = CommunityPostRequest(content = "수정 시도")
                )
            }
        }
    }

    @Test
    fun `커뮤니티 글 삭제 성공 - 크리에이터`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val follower = TestFixtures.createTestUser(email = "follower@test.com", username = "follower")

            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator.id.value)

            val post = communityService.createCommunityPost(
                userId = follower.id.value,
                creatorId = creator.id.value,
                request = CommunityPostRequest(content = "팔로워 글")
            )

            // When - 크리에이터가 팔로워 글 삭제
            communityService.deleteCommunityPost(post.id, creator.id.value)

            // Then - 삭제 확인
            assertFailsWith<PostNotFoundException> {
                communityService.updateCommunityPost(
                    postId = post.id,
                    userId = follower.id.value,
                    request = CommunityPostRequest(content = "수정 시도")
                )
            }
        }
    }

    @Test
    fun `커뮤니티 글 삭제 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val follower1 = TestFixtures.createTestUser(email = "follower1@test.com", username = "follower1")
            val follower2 = TestFixtures.createTestUser(email = "follower2@test.com", username = "follower2")

            TestFixtures.createTestFollow(followerId = follower1.id.value, followingId = creator.id.value)
            TestFixtures.createTestFollow(followerId = follower2.id.value, followingId = creator.id.value)

            val post = communityService.createCommunityPost(
                userId = follower1.id.value,
                creatorId = creator.id.value,
                request = CommunityPostRequest(content = "팔로워1 글")
            )

            // When & Then - 다른 팔로워가 삭제 시도
            assertFailsWith<ForbiddenException> {
                communityService.deleteCommunityPost(post.id, follower2.id.value)
            }
        }
    }

    // ===== 커뮤니티 조회 테스트 (2개) =====

    @Test
    fun `커뮤니티 조회 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val follower = TestFixtures.createTestUser(email = "follower@test.com", username = "follower")

            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator.id.value)

            // 커뮤니티 글 3개 작성
            repeat(3) { i ->
                communityService.createCommunityPost(
                    userId = follower.id.value,
                    creatorId = creator.id.value,
                    request = CommunityPostRequest(content = "글 ${i + 1}")
                )
            }

            // When
            val response = communityService.getCommunityPosts(
                creatorId = creator.id.value,
                currentUserId = follower.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(3, response.posts.items.size)
        }
    }

    @Test
    fun `커뮤니티 조회 성공 - 고정 글 포함`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()

            // 일반 글 작성
            val post1 = communityService.createCommunityPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                request = CommunityPostRequest(content = "일반 글")
            )

            // 공지 작성 및 고정
            val post2 = communityService.createCommunityPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                request = CommunityPostRequest(content = "공지 글")
            )
            communityService.togglePinCommunityPost(post2.id, creator.id.value)

            // When
            val response = communityService.getCommunityPosts(
                creatorId = creator.id.value,
                currentUserId = creator.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(1, response.pinnedPosts.size)
            assertEquals("공지 글", response.pinnedPosts[0].content)
        }
    }

    // ===== 공지 고정 테스트 (1개) =====

    @Test
    fun `커뮤니티 공지 고정 토글 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val post = communityService.createCommunityPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                request = CommunityPostRequest(content = "공지할 글")
            )

            // When - 고정
            val result1 = communityService.togglePinCommunityPost(post.id, creator.id.value)

            // Then
            assertEquals(result1["isPinned"], true)

            // When - 고정 해제
            val result2 = communityService.togglePinCommunityPost(post.id, creator.id.value)

            // Then
            assertEquals(result2["isPinned"], false)
        }
    }
}
