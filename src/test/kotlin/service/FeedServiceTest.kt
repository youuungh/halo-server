package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.FeedType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.InvalidHashtagException
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.*
import com.ninezero.features.tag.data.TagRepositoryImpl
import com.ninezero.features.social.domain.FeedService
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.social.presentation.models.request.FeedRequest
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
import kotlin.test.assertTrue

/**
 * FeedService 테스트
 *
 * 테스트 케이스: 21개
 * - 홈 피드: 4개
 * - 탐색 피드: 3개
 * - 트렌딩 피드: 3개
 * - 해시태그 피드: 4개
 * - 개인화 피드: 3개
 * - 사용자별 피드: 3개
 * - 피드 통계: 1개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedServiceTest {

    private lateinit var feedService: FeedService
    private lateinit var postService: PostService
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var likeRepository: LikeRepositoryImpl
    private lateinit var bookmarkRepository: BookmarkRepositoryImpl
    private lateinit var hiddenPostRepository: HiddenPostRepositoryImpl
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
            hiddenPostRepository = HiddenPostRepositoryImpl()
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

            coJustRun { fileUploadService.deleteFileIfSupabase(any()) }
            coEvery { imageProcessingService.validateImage(any(), any()) } returns true
            coEvery { imageProcessingService.validateVideo(any(), any()) } returns true
            coEvery { imageProcessingService.getFileExtension(any()) } returns "jpg"
            coEvery { cacheService.get<Any>(any(), any()) } returns null

            // PostService 초기화
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

            // FeedService 초기화
            feedService = FeedService(
                postRepository = postRepository,
                likeRepository = likeRepository,
                bookmarkRepository = bookmarkRepository,
                followRepository = followRepository,
                hiddenPostRepository = hiddenPostRepository,
                blockedUserRepository = blockedUserRepository,
                userRepository = userRepository,
                postService = postService,
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

    // ===== 홈 피드 테스트 (4개) =====

    @Test
    fun `홈 피드 조회 성공 - 팔로잉한 크리에이터 포스트 포함`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()

            // 팔로우
            TestFixtures.createTestFollow(followerId = user.id.value, followingId = creator.id.value)

            // 크리에이터 포스트 생성
            TestFixtures.createTestPost(userId = creator.id.value, content = "크리에이터 포스트 1")
            TestFixtures.createTestPost(userId = creator.id.value, content = "크리에이터 포스트 2")

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getHomeFeed(user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(FeedType.HOME, response.feedType)
            assertEquals(2, response.posts.size)
        }
    }

    @Test
    fun `홈 피드 조회 성공 - 자신의 포스트 포함`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // 자신의 포스트 생성
            TestFixtures.createTestPost(userId = user.id.value, content = "내 포스트 1")
            TestFixtures.createTestPost(userId = user.id.value, content = "내 포스트 2")

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getHomeFeed(user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(2, response.posts.size)
        }
    }

    @Test
    fun `홈 피드 조회 성공 - 팔로우 없는 경우 자신의 포스트만`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val otherUser = TestFixtures.createTestUser(email = "other@test.com", username = "other")

            // 다른 사용자 포스트는 팔로우 안했으므로 보이지 않아야 함
            TestFixtures.createTestPost(userId = otherUser.id.value, content = "다른 사용자 포스트")
            TestFixtures.createTestPost(userId = user.id.value, content = "내 포스트")

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getHomeFeed(user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(1, response.posts.size)
            assertEquals("내 포스트", response.posts[0].content)
        }
    }

    @Test
    fun `홈 피드 조회 성공 - 커서 기반 페이지네이션`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // 5개 포스트 생성
            repeat(5) { i ->
                TestFixtures.createTestPost(userId = user.id.value, content = "포스트 ${i + 1}")
            }

            // When - 첫 페이지 (2개씩)
            val firstPageRequest = FeedRequest(page = 1, limit = 2)
            val firstResponse = feedService.getHomeFeed(user.id.value, firstPageRequest)

            // Then
            assertNotNull(firstResponse)
            assertEquals(2, firstResponse.posts.size)
            assertTrue(firstResponse.hasNext)

            // When - 다음 페이지
            val lastPostId = firstResponse.lastPostId
            val secondPageRequest = FeedRequest(page = 2, limit = 2, lastPostId = lastPostId)
            val secondResponse = feedService.getHomeFeed(user.id.value, secondPageRequest)

            // Then
            assertNotNull(secondResponse)
            assertTrue(secondResponse.posts.isNotEmpty())
            assertTrue(secondResponse.posts.size <= 2)
        }
    }

    // ===== 탐색 피드 테스트 (3개) =====

    @Test
    fun `탐색 피드 조회 성공 - 인기 포스트 조회`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser()
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")

            // 다양한 포스트 생성
            val post1 = TestFixtures.createTestPost(userId = user1.id.value, content = "포스트 1")
            val post2 = TestFixtures.createTestPost(userId = user2.id.value, content = "포스트 2")

            // 좋아요 추가 (인기도 높이기)
            TestFixtures.createTestLike(userId = user1.id.value, postId = post2.id.value)

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getExploreFeed(user1.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(FeedType.EXPLORE, response.feedType)
            assertTrue(response.posts.isNotEmpty())
        }
    }

    @Test
    fun `탐색 피드 조회 성공 - 로그인하지 않은 사용자`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            TestFixtures.createTestPost(userId = user.id.value, content = "공개 포스트")

            val request = FeedRequest(page = 1, limit = 10)

            // When - userId = null (비로그인)
            val response = feedService.getExploreFeed(null, request)

            // Then
            assertNotNull(response)
            assertEquals(FeedType.EXPLORE, response.feedType)
        }
    }

    @Test
    fun `탐색 피드 조회 성공 - 페이지네이션`() {
        runBlocking {
            // Given
            val users = TestFixtures.createTestUsers(count = 3)

            // 각 사용자당 3개 포스트 생성
            users.forEach { user ->
                repeat(3) { i ->
                    TestFixtures.createTestPost(userId = user.id.value, content = "포스트 $i")
                }
            }

            // When
            val request = FeedRequest(page = 1, limit = 5)
            val response = feedService.getExploreFeed(users[0].id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(5, response.posts.size)
        }
    }

    // ===== 트렌딩 피드 테스트 (3개) =====

    @Test
    fun `트렌딩 피드 조회 성공 - 최근 인기 포스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // 최근 포스트 생성
            val post1 = TestFixtures.createTestPost(userId = user.id.value, content = "최근 포스트 1")
            val post2 = TestFixtures.createTestPost(userId = user.id.value, content = "최근 포스트 2")

            // 좋아요 추가
            TestFixtures.createTestLike(userId = user.id.value, postId = post1.id.value)

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getTrendingFeed(user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(FeedType.TRENDING, response.feedType)
            assertTrue(response.posts.isNotEmpty())
        }
    }

    @Test
    fun `트렌딩 피드 조회 성공 - 비로그인 사용자`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            TestFixtures.createTestPost(userId = user.id.value, content = "트렌딩 포스트")

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getTrendingFeed(null, request)

            // Then
            assertNotNull(response)
            assertEquals(FeedType.TRENDING, response.feedType)
        }
    }

    @Test
    fun `트렌딩 피드 조회 성공 - 빈 결과`() {
        runBlocking {
            // Given - 포스트 없음
            val user = TestFixtures.createTestUser()
            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getTrendingFeed(user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(0, response.posts.size)
        }
    }

    // ===== 해시태그 피드 테스트 (4개) =====

    @Test
    fun `해시태그 피드 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // 해시태그가 포함된 포스트 생성
            TestFixtures.createTestPost(
                userId = user.id.value,
                content = "내용 #테스트",
                tags = "#테스트"
            )
            TestFixtures.createTestPost(
                userId = user.id.value,
                content = "다른 내용 #개발",
                tags = "#개발"
            )

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getHashtagFeed("테스트", user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(FeedType.HASHTAG, response.feedType)
        }
    }

    @Test
    fun `해시태그 피드 조회 성공 - 비로그인 사용자`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            TestFixtures.createTestPost(
                userId = user.id.value,
                content = "#코틀린 테스트",
                tags = "#코틀린"
            )

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getHashtagFeed("코틀린", null, request)

            // Then
            assertNotNull(response)
            assertEquals(FeedType.HASHTAG, response.feedType)
        }
    }

    @Test
    fun `해시태그 피드 조회 실패 - 유효하지 않은 해시태그`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val invalidHashtag = ""
            val request = FeedRequest(page = 1, limit = 10)

            // When & Then
            assertFailsWith<InvalidHashtagException> {
                feedService.getHashtagFeed(invalidHashtag, user.id.value, request)
            }
        }
    }

    @Test
    fun `해시태그 피드 조회 성공 - 매칭 없음`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            TestFixtures.createTestPost(userId = user.id.value, content = "해시태그 없는 포스트")

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getHashtagFeed("존재하지않는태그", user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(0, response.posts.size)
        }
    }

    // ===== 개인화 피드 테스트 (3개) =====

    @Test
    fun `개인화 피드 조회 성공 - 선호도 없는 경우 홈 피드 반환`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            TestFixtures.createTestPost(userId = user.id.value, content = "내 포스트")

            val request = FeedRequest(page = 1, limit = 10)

            // When - 선호도 없음
            val response = feedService.getPersonalizedFeed(user.id.value, emptyList(), request)

            // Then
            assertNotNull(response)
            assertEquals(FeedType.HOME, response.feedType)
            assertEquals(1, response.posts.size)
        }
    }

    @Test
    fun `개인화 피드 조회 성공 - 선호 해시태그 기반`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            TestFixtures.createTestPost(
                userId = user.id.value,
                content = "#코틀린 포스트",
                tags = "#코틀린"
            )
            TestFixtures.createTestPost(
                userId = user.id.value,
                content = "#자바 포스트",
                tags = "#자바"
            )

            val preferences = listOf("코틀린")
            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getPersonalizedFeed(user.id.value, preferences, request)

            // Then
            assertNotNull(response)
            assertEquals(FeedType.HOME, response.feedType)
        }
    }

    @Test
    fun `개인화 피드 조회 성공 - 여러 선호도 조합`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            TestFixtures.createTestPost(
                userId = user.id.value,
                content = "#개발 포스트",
                tags = "#개발"
            )
            TestFixtures.createTestPost(
                userId = user.id.value,
                content = "#디자인 포스트",
                tags = "#디자인"
            )

            val preferences = listOf("개발", "디자인")
            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getPersonalizedFeed(user.id.value, preferences, request)

            // Then
            assertNotNull(response)
        }
    }

    // ===== 사용자별 피드 테스트 (3개) =====

    @Test
    fun `사용자별 피드 조회 성공 - 자신의 포스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            repeat(3) { i ->
                TestFixtures.createTestPost(userId = user.id.value, content = "포스트 ${i + 1}")
            }

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getUserFeed(user.id.value, user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(FeedType.USER_POSTS, response.feedType)
            assertEquals(3, response.posts.size)
        }
    }

    @Test
    fun `사용자별 피드 조회 성공 - 다른 사용자 포스트 (FREE 티어만)`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val viewer = TestFixtures.createTestUser(email = "viewer@test.com", username = "viewer")

            // FREE 포스트
            TestFixtures.createTestPost(
                userId = creator.id.value,
                content = "무료 포스트",
                requiredTier = SubscriptionPlanTier.FREE
            )

            // TIER1 포스트 (구독 필요)
            TestFixtures.createTestPost(
                userId = creator.id.value,
                content = "유료 포스트",
                requiredTier = SubscriptionPlanTier.TIER1
            )

            val request = FeedRequest(page = 1, limit = 10)

            // When - viewer는 구독하지 않음
            val response = feedService.getUserFeed(creator.id.value, viewer.id.value, request)

            // Then - FREE 포스트만 보여야 함
            assertNotNull(response)
            assertEquals(1, response.posts.size)
            assertEquals("무료 포스트", response.posts[0].content)
        }
    }

    @Test
    fun `사용자별 피드 조회 성공 - 비로그인 사용자 (FREE 티어만)`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()

            TestFixtures.createTestPost(
                userId = creator.id.value,
                content = "무료 포스트",
                requiredTier = SubscriptionPlanTier.FREE
            )
            TestFixtures.createTestPost(
                userId = creator.id.value,
                content = "유료 포스트",
                requiredTier = SubscriptionPlanTier.TIER1
            )

            val request = FeedRequest(page = 1, limit = 10)

            // When - 비로그인
            val response = feedService.getUserFeed(creator.id.value, null, request)

            // Then - FREE 포스트만 보여야 함
            assertNotNull(response)
            assertEquals(1, response.posts.size)
            assertEquals("무료 포스트", response.posts[0].content)
        }
    }

    // ===== 피드 통계 테스트 (1개) =====

    @Test
    fun `피드 통계 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()

            // 팔로우
            TestFixtures.createTestFollow(followerId = user.id.value, followingId = creator.id.value)

            // 포스트 생성
            TestFixtures.createTestPost(userId = user.id.value, content = "내 포스트")
            TestFixtures.createTestPost(userId = creator.id.value, content = "크리에이터 포스트")

            // When
            val stats = feedService.getFeedStats(user.id.value)

            // Then
            assertNotNull(stats)
            assertTrue(stats["homeFeedAvailable"] as Boolean)
            assertEquals(1, stats["userPostCount"])
            assertTrue(stats["hasUserPosts"] as Boolean)
            assertNotNull(stats["feedLastUpdated"])
        }
    }
}