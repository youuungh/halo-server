package com.ninezero.service

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.core.database.entities.upload.MediaDraftDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.*
import com.ninezero.features.social.domain.CommunityService
import com.ninezero.features.social.domain.PostMediaService
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.social.presentation.models.request.CommunityPostRequest
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.tag.data.TagRepositoryImpl
import com.ninezero.features.upload.data.MediaDraftRepositoryImpl
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityServiceTest {

    private lateinit var communityService: CommunityService
    private lateinit var postService: PostService
    private lateinit var postMediaService: PostMediaService
    private lateinit var mediaDraftService: MediaDraftService
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var likeRepository: LikeRepositoryImpl
    private lateinit var bookmarkRepository: BookmarkRepositoryImpl
    private lateinit var followRepository: FollowRepositoryImpl

    // Mock Services
    private lateinit var fileUploadService: FileUploadService
    private lateinit var imageProcessingService: ImageProcessingService
    private lateinit var cacheService: CacheService

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

            coJustRun { fileUploadService.deleteByUrl(any()) }
            coEvery { imageProcessingService.validateImage(any(), any()) } returns true
            coEvery { cacheService.get<Any>(any(), any()) } returns null

            // 미디어 수정은 PostService에 위임한다
            every { imageProcessingService.extractImageDimensions(any()) } returns (100 to 100)
            coEvery { imageProcessingService.generateDisplayImage(any(), any()) } returns null
            coEvery { imageProcessingService.generateLockedPreview(any()) } returns null
            coEvery { fileUploadService.uploadPostMedia(any(), any(), any()) } returns COMMUNITY_MEDIA_URL

            mediaDraftService = MediaDraftService(
                mediaDraftRepository = MediaDraftRepositoryImpl(),
                mediaUploadService = mockk(relaxed = true),
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
                videoProcessingService = mockk(relaxed = true),
                coroutineScope = CoroutineScope(Dispatchers.Unconfined)
            )

            postService = PostService(
                userRepository = userRepository,
                postRepository = postRepository,
                likeRepository = likeRepository,
                bookmarkRepository = bookmarkRepository,
                followRepository = followRepository,
                tagRepository = TagRepositoryImpl(),
                subscriptionRepository = SubscriptionRepositoryImpl(),
                planRepository = SubscriptionPlanRepositoryImpl(),
                blockedUserRepository = BlockedUserRepositoryImpl(),
                notificationService = mockk<NotificationService>(relaxed = true),
                mediaDraftService = mediaDraftService,
                fileUploadService = fileUploadService,
                videoProcessingService = mockk(relaxed = true),
                cacheService = cacheService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined)
            )

            postMediaService = PostMediaService(
                postRepository = postRepository,
                userRepository = userRepository,
                likeRepository = likeRepository,
                bookmarkRepository = bookmarkRepository,
                mediaUploadService = mockk(relaxed = true),
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
                videoProcessingService = mockk<VideoProcessingService>(relaxed = true),
                cacheService = cacheService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined)
            )

            communityService = CommunityService(
                postRepository = postRepository,
                userRepository = userRepository,
                likeRepository = likeRepository,
                bookmarkRepository = bookmarkRepository,
                followRepository = followRepository,
                blockedUserRepository = BlockedUserRepositoryImpl(),
                postService = postService,
                postMediaService = postMediaService,
                mediaDraftService = mediaDraftService,
                fileUploadService = fileUploadService,
                videoProcessingService = mockk(relaxed = true),
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

    // ===== 커뮤니티 글 작성 테스트 =====

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
    fun `커뮤니티 글 작성 - 초안을 미디어에 붙이고 같은 요청 식별자는 같은 글을 돌려준다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val fan = TestFixtures.createTestUser(email = "fan@test.com", username = "fan")
            query { followRepository.createFollow(fan.id.value, creator.id.value) }
            val draft = TestFixtures.createTestMediaDraft(fan.id.value)
            val request = CommunityPostRequest(content = "초안 글", mediaDraftIds = listOf(draft.id.value), clientRequestId = "pending-1")

            val response = communityService.createCommunityPost(fan.id.value, creator.id.value, request)
            val again = communityService.createCommunityPost(fan.id.value, creator.id.value, request)

            assertEquals(listOf(draft.url), response.mediaItems.map { it.url })
            assertEquals(response.id, again.id)
            assertEquals(1L, query { PostDao.all().count() })
            assertEquals(0L, query { MediaDraftDao.all().count() })
        }
    }

    // ===== 커뮤니티 글 수정 테스트 =====

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

    // ===== 커뮤니티 글 삭제 테스트 =====

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

    @Test
    fun `커뮤니티 글 작성·수정·삭제는 탐색 피드와 작성자 프로필 캐시도 지운다`() {
        runBlocking {
            clearMocks(cacheService, answers = false)
            val creator = TestFixtures.createTestCreator()
            val follower = TestFixtures.createTestUser(email = "follower@test.com", username = "follower")
            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator.id.value)

            val post = communityService.createCommunityPost(follower.id.value, creator.id.value, CommunityPostRequest(content = "팔로워 글"))
            coVerify(exactly = 1) { cacheService.deletePattern(CacheKeys.Patterns.EXPLORE_FEED) }
            coVerify(exactly = 1) { cacheService.delete(CacheKeys.profile(follower.id.value)) }

            communityService.updateCommunityPost(post.id, follower.id.value, CommunityPostRequest(content = "수정한 글"))
            coVerify(exactly = 2) { cacheService.deletePattern(CacheKeys.Patterns.EXPLORE_FEED) }
            coVerify(exactly = 2) { cacheService.delete(CacheKeys.profile(follower.id.value)) }

            // 크리에이터가 지워도 글 수가 바뀌는 건 작성자 프로필
            communityService.deleteCommunityPost(post.id, creator.id.value)
            coVerify(exactly = 3) { cacheService.deletePattern(CacheKeys.Patterns.EXPLORE_FEED) }
            coVerify(exactly = 3) { cacheService.delete(CacheKeys.profile(follower.id.value)) }
        }
    }

    // ===== 커뮤니티 조회 테스트 =====

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
                limit = 10
            )

            // Then
            assertEquals(1, response.pinnedPosts.size)
            assertEquals("공지 글", response.pinnedPosts[0].content)
        }
    }

    @Test
    fun `커뮤니티 조회 - 과도한 limit은 최대치로 제한`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            repeat(Constants.MAX_PAGE_LIMIT + 1) {
                TestFixtures.createTestPost(
                    userId = creator.id.value,
                    creatorId = creator.id.value,
                    contextType = PostContextType.COMMUNITY
                )
            }

            val response = communityService.getCommunityPosts(
                creatorId = creator.id.value,
                currentUserId = creator.id.value,
                limit = 5000
            )

            assertEquals(Constants.MAX_PAGE_LIMIT, response.posts.items.size)
        }
    }

    @Test
    fun `커뮤니티 목록에서 차단 관계 사용자의 글은 빠지고 관심없음 처리한 글은 남는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val (viewer, blockedFan, fan) = TestFixtures.createTestUsers(count = 3)
            listOf(viewer, blockedFan, fan).forEach {
                TestFixtures.createTestFollow(followerId = it.id.value, followingId = creator.id.value)
            }
            val visiblePost = communityService.createCommunityPost(fan.id.value, creator.id.value, CommunityPostRequest(content = "보이는 글"))
            val hiddenPost = communityService.createCommunityPost(fan.id.value, creator.id.value, CommunityPostRequest(content = "관심없음 글"))
            communityService.createCommunityPost(blockedFan.id.value, creator.id.value, CommunityPostRequest(content = "차단한 팬 글"))
            val notice = communityService.createCommunityPost(creator.id.value, creator.id.value, CommunityPostRequest(content = "공지"))
            communityService.togglePinCommunityPost(notice.id, creator.id.value)
            query {
                BlockedUserRepositoryImpl().createBlock(blockedFan.id.value, viewer.id.value)
                HiddenPostRepositoryImpl().createHiddenPost(viewer.id.value, hiddenPost.id)
            }

            // 빠진 글이 최신이어도 한 페이지는 남은 글로 채움, 관심없음은 피드에서만
            val response = communityService.getCommunityPosts(creator.id.value, viewer.id.value, limit = 2)

            assertEquals(listOf(hiddenPost.id, visiblePost.id), response.posts.items.map { it.id })
            assertEquals(false, response.posts.hasNext)
        }
    }

    // ===== 공지 고정 테스트 =====

    @Test
    fun `커뮤니티 공지 고정 토글은 고정 여부가 담긴 포스트 상세 캐시를 지운다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val post = communityService.createCommunityPost(creator.id.value, creator.id.value, CommunityPostRequest(content = "공지할 글"))
            clearMocks(cacheService, answers = false)

            communityService.togglePinCommunityPost(post.id, creator.id.value)

            coVerify(exactly = 1) { cacheService.deletePattern(CacheKeys.Patterns.post(post.id)) }
        }
    }

    @Test
    fun `고정 공지를 지우면 커뮤니티 고정 한도가 돌아온다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val pinned = List(Constants.Social.MAX_PINNED_COMMUNITY_POSTS) { i ->
                communityService.createCommunityPost(creator.id.value, creator.id.value, CommunityPostRequest(content = "공지 $i"))
                    .also { communityService.togglePinCommunityPost(it.id, creator.id.value) }
            }

            communityService.deleteCommunityPost(pinned.first().id, creator.id.value)

            val next = communityService.createCommunityPost(creator.id.value, creator.id.value, CommunityPostRequest(content = "새 공지"))
            assertEquals(true, communityService.togglePinCommunityPost(next.id, creator.id.value).isPinned)
        }
    }

    @Test
    fun `커뮤니티 공지 고정 실패 - 커뮤니티 글이 아님`() {
        runBlocking {
            // Given - 같은 크리에이터의 피드 글
            val creator = TestFixtures.createTestCreator()
            val post = TestFixtures.createTestPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                contextType = PostContextType.CREATOR_FEED
            )

            // When & Then
            assertFailsWith<ForbiddenException> {
                communityService.togglePinCommunityPost(post.id.value, creator.id.value)
            }
        }
    }

    @Test
    fun `커뮤니티 공지 고정 실패 - 비밀글`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val post = communityService.createCommunityPost(creator.id.value, creator.id.value, CommunityPostRequest(content = "비밀글", isSecret = true))

            assertFailsWith<InvalidInputException> {
                communityService.togglePinCommunityPost(post.id, creator.id.value)
            }
        }
    }

    // ===== 커뮤니티 미디어 수정 테스트 =====

    @Test
    fun `커뮤니티 글 미디어 수정 실패 - 다른 커뮤니티 글`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val otherCreator = TestFixtures.createTestCreator(email = "other@test.com", username = "othercreator")
            val post = communityService.createCommunityPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                request = CommunityPostRequest(content = "크리에이터 글")
            )

            // When & Then
            assertFailsWith<ForbiddenException> {
                communityService.updateCommunityWithMedia(
                    postId = post.id,
                    userId = creator.id.value,
                    creatorId = otherCreator.id.value,
                    content = "수정 시도",
                    keepMediaIds = null,
                    newFiles = null,
                    newContentTypes = null,
                    reorder = null
                )
            }
        }
    }
}

private const val COMMUNITY_MEDIA_URL = "https://test.supabase.co/storage/v1/object/public/posts/community.jpg"
