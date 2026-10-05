package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.FeedType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.exception.InvalidHashtagException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.PostDao
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
import kotlin.test.assertTrue

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

            coJustRun { fileUploadService.deleteByUrl(any()) }
            coEvery { imageProcessingService.validateImage(any(), any()) } returns true
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
                mediaDraftService = mockk(relaxed = true),
                fileUploadService = fileUploadService,
                videoProcessingService = mockk(relaxed = true),
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

    // ===== 홈 피드 테스트 =====

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

            // When
            val response = feedService.getHomeFeed(user.id.value, limit = 10)

            // Then
            assertEquals(2, response.items.size)
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

            // When
            val response = feedService.getHomeFeed(user.id.value, limit = 10)

            // Then
            assertEquals(1, response.items.size)
            assertEquals("내 포스트", response.items[0].content)
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
            val firstResponse = feedService.getHomeFeed(user.id.value, limit = 2)

            // Then
            assertEquals(2, firstResponse.items.size)
            assertTrue(firstResponse.hasNext)

            // When - 다음 페이지
            val secondResponse = feedService.getHomeFeed(user.id.value, cursor = firstResponse.nextCursor, limit = 2)

            // Then - 겹치지 않는 다음 2개
            assertEquals(2, secondResponse.items.size)
            assertTrue(firstResponse.items.map { it.id }.intersect(secondResponse.items.map { it.id }.toSet()).isEmpty())
        }
    }

    // ===== 탐색 피드 테스트 =====

    @Test
    fun `탐색 피드 조회 성공 - 인기 포스트 조회`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser()
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")

            // 다양한 포스트 생성
            val post1 = TestFixtures.createTestPost(userId = user1.id.value, content = "포스트 1")
            val post2 = TestFixtures.createTestPost(userId = user2.id.value, content = "포스트 2")

            // 오래된 글의 좋아요 수를 올림(순위는 글의 좋아요 수 컬럼 기준)
            query { PostDao.findById(post1.id.value)!!.likeCount = 1 }

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getExploreFeed(user1.id.value, request)

            // Then - 최신 글보다 좋아요 받은 글이 앞
            assertEquals(FeedType.EXPLORE, response.feedType)
            assertEquals(listOf(post1.id.value, post2.id.value), response.posts.map { it.id })
        }
    }

    // ===== 트렌딩 피드 테스트 =====

    @Test
    fun `트렌딩 피드 조회 성공 - 최근 인기 포스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // 최근 포스트 생성
            val post1 = TestFixtures.createTestPost(userId = user.id.value, content = "최근 포스트 1")
            val post2 = TestFixtures.createTestPost(userId = user.id.value, content = "최근 포스트 2")

            // 오래된 글의 좋아요 수를 올림(순위는 글의 좋아요 수 컬럼 기준)
            query { PostDao.findById(post1.id.value)!!.likeCount = 1 }

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getTrendingFeed(user.id.value, request)

            // Then - 최신 글보다 좋아요 받은 글이 앞
            assertEquals(FeedType.TRENDING, response.feedType)
            assertEquals(listOf(post1.id.value, post2.id.value), response.posts.map { it.id })
        }
    }

    // ===== 해시태그 피드 테스트 =====

    @Test
    fun `해시태그 피드 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // 해시태그가 포함된 포스트 생성
            TestFixtures.createTestPost(
                userId = user.id.value,
                content = "내용 #테스트",
                tags = """["테스트"]"""
            )
            TestFixtures.createTestPost(
                userId = user.id.value,
                content = "다른 내용 #개발",
                tags = """["개발"]"""
            )

            val request = FeedRequest(page = 1, limit = 10)

            // When
            val response = feedService.getHashtagFeed("테스트", user.id.value, request)

            // Then
            assertEquals(FeedType.HASHTAG, response.feedType)
            assertEquals(listOf("내용 #테스트"), response.posts.map { it.content })
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
    fun `커뮤니티 글은 홈·탐색·트렌딩·해시태그 피드에 나오지 않는다`() {
        runBlocking {
            val viewer = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val fan = TestFixtures.createTestUser(email = "fan@example.com", username = "fan")
            TestFixtures.createTestFollow(followerId = viewer.id.value, followingId = fan.id.value)
            val generalPost = TestFixtures.createTestPost(userId = fan.id.value, content = "일반 글 #소식")
            TestFixtures.createTestPost(
                userId = fan.id.value,
                creatorId = creator.id.value,
                content = "커뮤니티 글 #소식",
                contextType = PostContextType.COMMUNITY
            )
            val request = FeedRequest(page = 1, limit = 10)

            val shown = mapOf(
                "홈" to feedService.getHomeFeed(viewer.id.value, limit = 10).items,
                "탐색" to feedService.getExploreFeed(viewer.id.value, request).posts,
                "트렌딩" to feedService.getTrendingFeed(viewer.id.value, request).posts,
                "해시태그" to feedService.getHashtagFeed("소식", viewer.id.value, request).posts
            ).mapValues { (_, posts) -> posts.map { it.id } }

            val expected = listOf("홈", "탐색", "트렌딩", "해시태그").associateWith { listOf(generalPost.id.value) }
            assertEquals(expected, shown)
        }
    }
}
