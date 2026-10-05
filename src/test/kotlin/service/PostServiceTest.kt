package com.ninezero.service

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.MediaType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.common.config.UploadStatus
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.CreatorOnlyException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.InvalidPostContentException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.exception.ValidationException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.database.entities.upload.MediaDraftDao
import com.ninezero.core.database.entities.upload.MediaUploadDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.BookmarkRepositoryImpl
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.LikeRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.tag.data.TagRepositoryImpl
import com.ninezero.features.social.domain.PostMediaService
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.upload.data.MediaDraftRepositoryImpl
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.upload.presentation.models.request.VideoUploadPrepareRequest
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.social.presentation.models.request.MediaItemRef
import com.ninezero.features.social.presentation.models.request.PostRequest
import com.ninezero.features.social.presentation.models.request.UpdatePostRequest
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.upload.data.MediaUploadRepositoryImpl
import com.ninezero.features.upload.domain.MediaUploadService
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertNotNull
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
class PostServiceTest {

    private lateinit var postService: PostService
    private lateinit var postMediaService: PostMediaService
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
    private lateinit var mediaUploadService: MediaUploadService
    private lateinit var mediaDraftService: MediaDraftService
    private val backgroundJob = SupervisorJob()  // 알림 등 비동기 작업 완료 대기용

    // 가짜 다운로드가 만든 임시파일, 재인코딩 뒤 삭제 확인용
    private val downloadedFiles = mutableListOf<File>()
    private val mp4Head = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D, 0, 0, 0, 0)

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
            coJustRun { fileUploadService.deleteByUrl(any()) }
            coEvery { imageProcessingService.validateImage(any(), any()) } returns true
            coEvery { cacheService.get<Any>(any(), any()) } returns null

            // 직접 업로드 경로
            coEvery { fileUploadService.fileSize(any(), any()) } returns 1000L
            coEvery { fileUploadService.readHead(any(), any(), any()) } returns mp4Head
            coEvery { fileUploadService.uploadPostMedia(any(), any(), any()) } answers {
                imageUrl(firstArg(), secondArg<ByteArray>().size)
            }
            every { imageProcessingService.extractImageDimensions(any()) } returns (100 to 100)
            coEvery { imageProcessingService.generateDisplayImage(any(), any()) } returns null
            coEvery { imageProcessingService.generateLockedPreview(any()) } returns null
            coEvery { videoProcessingService.optimizeForStreaming(any<File>()) } returns null
            coEvery { videoProcessingService.extractMetadata(any<File>()) } returns
                VideoProcessingService.VideoMetadata(width = 1080, height = 1920, durationMs = 5000)
            coEvery { videoProcessingService.extractThumbnail(any<File>()) } returns null
            coEvery { videoProcessingService.transcodeForStreaming(any<File>()) } returns null

            mediaUploadService = MediaUploadService(
                mediaUploadRepository = MediaUploadRepositoryImpl(),
                fileUploadService = fileUploadService,
                imageProcessingService = ImageProcessingService()  // 영상 머리 검사는 실제 구현
            )
            mediaDraftService = MediaDraftService(
                mediaDraftRepository = MediaDraftRepositoryImpl(),
                mediaUploadService = mediaUploadService,
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
                videoProcessingService = videoProcessingService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + backgroundJob)
            )

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
                mediaDraftService = mediaDraftService,
                fileUploadService = fileUploadService,
                videoProcessingService = videoProcessingService,
                cacheService = cacheService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + backgroundJob)
            )

            postMediaService = PostMediaService(
                postRepository = postRepository,
                userRepository = userRepository,
                likeRepository = likeRepository,
                bookmarkRepository = bookmarkRepository,
                mediaUploadService = mediaUploadService,
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
                videoProcessingService = videoProcessingService,
                cacheService = cacheService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + backgroundJob)
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()

            downloadedFiles.clear()
            coEvery { fileUploadService.downloadToTempFile(any(), any(), any()) } answers {
                File.createTempFile("test_src", ".tmp").also { downloadedFiles += it }
            }
            coEvery { fileUploadService.downloadToTempFile(any<String>()) } answers {
                File.createTempFile("test_src", ".tmp").also { downloadedFiles += it }
            }
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    // ===== 태그 소유권 테스트 =====

    @Test
    fun `남의 크리에이터 태그는 포스트에 붙일 수 없음`() {
        runBlocking {
            val owner = TestFixtures.createTestCreator()
            val attacker = TestFixtures.createTestCreator(email = "other@example.com", username = "other")
            val ownedTag = query {
                tagRepository.createTag(owner.id.value, "남의태그", TagTargetType.POST, isSectionEnabled = true)
            }

            val request = PostRequest(
                content = "남의 태그를 붙여본다",
                postType = PostType.TEXT,
                productId = null,
                requiredTier = SubscriptionPlanTier.FREE,
                tagIds = listOf(ownedTag.id.value),
                sectionTagId = ownedTag.id.value
            )

            assertFailsWith<ForbiddenException> {
                postService.createPost(attacker.id.value, request)
            }
        }
    }

    @Test
    fun `자기 태그는 포스트에 붙일 수 있음`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val ownTag = query {
                tagRepository.createTag(creator.id.value, "내태그", TagTargetType.POST, isSectionEnabled = true)
            }

            val request = PostRequest(
                content = "내 태그를 붙인다",
                postType = PostType.TEXT,
                productId = null,
                requiredTier = SubscriptionPlanTier.FREE,
                tagIds = listOf(ownTag.id.value),
                sectionTagId = ownTag.id.value
            )

            postService.createPost(creator.id.value, request)

            assertEquals(1, query { tagRepository.countPostsByTag(ownTag.id.value) })
        }
    }

    // ===== 포스트 생성 테스트 =====

    @Test
    fun `포스트 작성 실패 - 사진·영상 글은 초안 없이 만들 수 없다`() {
        runBlocking {
            clearMocks(notificationService, answers = false)
            val creator = TestFixtures.createTestCreator()

            assertFailsWith<InvalidInputException> {
                postService.createPost(creator.id.value, PostRequest(content = "사진 글입니다", postType = PostType.IMAGE))
            }

            assertEquals(0L, query { PostDao.all().count() })
            coVerify(exactly = 0) { notificationService.sendNewPostNotifications(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `포스트 작성 - 초안을 지정한 순서로 미디어에 붙이고 초안은 지우며 알림은 바로 보낸다`() {
        runBlocking {
            clearMocks(notificationService, answers = false)
            val creator = TestFixtures.createTestCreator()
            val follower = TestFixtures.createTestUser()
            query { followRepository.createFollow(follower.id.value, creator.id.value) }
            val image = TestFixtures.createTestMediaDraft(creator.id.value)
            val video = TestFixtures.createTestMediaDraft(creator.id.value, type = MediaType.VIDEO, url = imageUrl(0, 9))

            val response = postService.createPost(
                creator.id.value,
                PostRequest(content = "초안 글", postType = PostType.VIDEO, mediaDraftIds = listOf(video.id.value, image.id.value))
            )

            assertEquals(listOf(video.url, image.url), response.mediaItems.map { it.url })
            assertEquals(listOf(0, 1), response.mediaItems.map { it.sortOrder })
            assertEquals(2L, query { PostMediaDao.find { PostMediaTable.postId eq response.id }.count() })
            assertEquals(0L, query { MediaDraftDao.all().count() })
            coVerify(exactly = 1, timeout = 2000) {
                notificationService.sendNewPostNotifications(creator.id.value, any(), response.id, listOf(follower.id.value))
            }
            // 영상은 저장소에서 다시 받아 재인코딩
            coVerify { fileUploadService.downloadToTempFile(video.url) }
            coVerify { videoProcessingService.transcodeForStreaming(any<File>()) }
            assertTrue(downloadedFiles.isNotEmpty() && downloadedFiles.none { it.exists() })
        }
    }

    @Test
    fun `포스트 작성 실패 - 남의 초안이나 텍스트 글의 초안이면 글도 생기지 않는다`() {
        runBlocking {
            val (user, other) = TestFixtures.createTestUsers(count = 2)
            val draft = TestFixtures.createTestMediaDraft(other.id.value)

            assertFailsWith<NotFoundException> {
                postService.createPost(user.id.value, PostRequest(content = "남의 초안", postType = PostType.IMAGE, mediaDraftIds = listOf(draft.id.value)))
            }
            assertFailsWith<InvalidInputException> {
                postService.createPost(user.id.value, PostRequest(content = "텍스트", postType = PostType.TEXT, mediaDraftIds = listOf(draft.id.value)))
            }

            assertEquals(0L, query { PostDao.all().count() })
            assertNotNull(query { MediaDraftDao.findById(draft.id.value) })
        }
    }

    @Test
    fun `포스트 작성 - 같은 요청 식별자는 새 글 없이 만든 글을 돌려준다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val request = PostRequest(content = "한 번만", clientRequestId = "pending-1")

            val first = postService.createPost(user.id.value, request)
            val again = postService.createPost(user.id.value, request)

            assertEquals(first.id, again.id)
            assertEquals(1L, query { PostDao.all().count() })
            assertFailsWith<InvalidInputException> {
                postService.createPost(user.id.value, PostRequest(content = "너무 긴 식별자", clientRequestId = "x".repeat(65)))
            }
        }
    }

    @Test
    fun `커뮤니티 글 미디어 수정은 커뮤니티 목록 캐시를 지운다`() {
        runBlocking {
            clearMocks(cacheService, answers = false)
            val creator = TestFixtures.createTestCreator()
            val author = TestFixtures.createTestUser()
            val postId = query {
                postRepository.createCommunityPost(
                    userId = author.id.value, creatorId = creator.id.value, content = "커뮤니티 사진 글",
                    postType = PostType.IMAGE, tags = emptyList(), isSecret = false
                ).id.value
            }

            attachByUpdate(postId, author.id.value, files = listOf(byteArrayOf(1)))

            coVerify(exactly = 1) { cacheService.deletePattern(CacheKeys.Patterns.community(creator.id.value)) }
        }
    }

    @Test
    fun `커뮤니티 글을 글 경로로 수정하거나 지우면 커뮤니티 목록 캐시를 지운다`() {
        runBlocking {
            clearMocks(cacheService, answers = false)
            val creator = TestFixtures.createTestCreator()
            val author = TestFixtures.createTestUser()
            val postId = query {
                postRepository.createCommunityPost(
                    userId = author.id.value, creatorId = creator.id.value, content = "커뮤니티 글",
                    postType = PostType.IMAGE, tags = emptyList(), isSecret = false
                ).id.value
            }

            postService.updatePost(postId, author.id.value, UpdatePostRequest(content = "수정한 글"))
            coVerify(exactly = 1) { cacheService.deletePattern(CacheKeys.Patterns.community(creator.id.value)) }

            postMediaService.updatePostWithMedia(
                postId = postId, userId = author.id.value, content = "다시 수정한 글",
                keepMediaIds = null, newFiles = null, newContentTypes = null, reorder = null
            )
            coVerify(exactly = 2) { cacheService.deletePattern(CacheKeys.Patterns.community(creator.id.value)) }

            postService.deletePost(postId, author.id.value)
            coVerify(exactly = 3) { cacheService.deletePattern(CacheKeys.Patterns.community(creator.id.value)) }
        }
    }

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
            assertEquals("안녕하세요! 첫 번째 포스트입니다.", response.content)
            assertEquals(PostType.TEXT, response.postType)
            assertEquals(PostContextType.GENERAL, response.contextType)
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
            assertEquals("크리에이터 포스트입니다!", response.content)
            assertEquals(PostContextType.CREATOR_FEED, response.contextType)
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
    fun `포스트 생성 실패 - 그 등급의 활성 플랜이 없는 크리에이터`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()

            val request = PostRequest(
                content = "TIER1 전용 글",
                postType = PostType.TEXT,
                productId = null,
                requiredTier = SubscriptionPlanTier.TIER1,
                tagIds = null,
                sectionTagId = null
            )

            // When & Then
            assertFailsWith<ValidationException> {
                postService.createPost(creator.id.value, request)
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

    // ===== 포스트 조회 테스트 =====

    @Test
    fun `사용자 포스트 목록 - 구독하지 않은 사용자에게 유료 글은 잠김`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val viewer = TestFixtures.createTestUser(email = "viewer@test.com", username = "viewer")
            val freePost = TestFixtures.createTestPost(userId = creator.id.value, requiredTier = SubscriptionPlanTier.FREE)
            val paidPost = TestFixtures.createTestPost(userId = creator.id.value, requiredTier = SubscriptionPlanTier.TIER1)

            // When
            val response = postService.getUserPosts(targetUserId = creator.id.value, currentUserId = viewer.id.value)

            // Then
            val accessById = response.items.associate { it.id to it.canAccess }
            assertEquals(true, accessById[freePost.id.value])
            assertEquals(false, accessById[paidPost.id.value])
        }
    }

    @Test
    fun `사용자 포스트 목록 - 비로그인 사용자에게 유료 글은 잠김`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val freePost = TestFixtures.createTestPost(userId = creator.id.value, requiredTier = SubscriptionPlanTier.FREE)
            val paidPost = TestFixtures.createTestPost(userId = creator.id.value, requiredTier = SubscriptionPlanTier.TIER1)

            // When
            val response = postService.getUserPosts(targetUserId = creator.id.value, currentUserId = null)

            // Then
            val accessById = response.items.associate { it.id to it.canAccess }
            assertEquals(true, accessById[freePost.id.value])
            assertEquals(false, accessById[paidPost.id.value])
        }
    }

    @Test
    fun `크리에이터 프로필 - 과도한 limit은 최대치로 제한`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            repeat(Constants.MAX_PAGE_LIMIT + 1) {
                TestFixtures.createTestPost(
                    userId = creator.id.value,
                    creatorId = creator.id.value,
                    contextType = PostContextType.CREATOR_FEED
                )
            }

            val response = postService.getCreatorProfileSections(creator.id.value, currentUserId = null, page = 1, limit = 5000)

            assertEquals(Constants.MAX_PAGE_LIMIT, response.allPosts.items.size)
        }
    }

    @Test
    fun `크리에이터 프로필 - 전체 글 개수는 목록과 같이 피드 글만 센다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            repeat(2) {
                TestFixtures.createTestPost(
                    userId = creator.id.value,
                    creatorId = creator.id.value,
                    contextType = PostContextType.CREATOR_FEED
                )
            }
            repeat(3) {
                TestFixtures.createTestPost(
                    userId = creator.id.value,
                    creatorId = creator.id.value,
                    contextType = PostContextType.COMMUNITY
                )
            }

            val response = postService.getCreatorProfileSections(creator.id.value, currentUserId = null, page = 1, limit = 10)

            assertEquals(2, response.allPosts.items.size)
            assertEquals(2, response.allPosts.totalCount)
        }
    }

    @Test
    fun `크리에이터 프로필 - 섹션 전체보기는 섹션으로 붙은 글만 센다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val (daily, other) = query {
                tagRepository.createTag(creator.id.value, "일상", TagTargetType.POST, isSectionEnabled = true) to
                    tagRepository.createTag(creator.id.value, "기타", TagTargetType.POST, isSectionEnabled = true)
            }
            repeat(3) {
                val post = TestFixtures.createTestPost(creator.id.value, creator.id.value, contextType = PostContextType.CREATOR_FEED)
                query { tagRepository.attachTagsToPost(post.id.value, listOf(daily.id.value), sectionTagId = daily.id.value) }
            }
            // 다른 섹션 글에 곁다리로만 붙은 연결
            repeat(Constants.Social.MAX_ITEMS_PER_SECTION) {
                val post = TestFixtures.createTestPost(creator.id.value, creator.id.value, contextType = PostContextType.CREATOR_FEED)
                query { tagRepository.attachTagsToPost(post.id.value, listOf(other.id.value, daily.id.value), sectionTagId = other.id.value) }
            }

            val section = postService.getCreatorProfileSections(creator.id.value, currentUserId = null, page = 1, limit = 10)
                .tagSections.single { it.tag.id == daily.id.value }

            assertEquals(3, section.posts.size)
            assertEquals(3, section.tag.postCount)
            assertFalse(section.hasMore)
        }
    }

    @Test
    fun `일괄 조회는 요청한 id 순서를 유지한다 - 포스트·상품`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val posts = TestFixtures.createTestPosts(creator.id.value, 3)
            val products = TestFixtures.createTestProducts(creator.id.value, 3)
            val postIds = listOf(posts[2].id.value, posts[0].id.value, posts[1].id.value)
            val productIds = listOf(products[1].id.value, products[2].id.value, products[0].id.value)

            val foundPostIds = query { postRepository.findPostsByIds(postIds).map { it.id.value } }
            val foundProductIds = query { ProductRepositoryImpl().findProductsByIds(productIds).map { it.id.value } }

            assertEquals(postIds, foundPostIds)
            assertEquals(productIds, foundProductIds)
        }
    }

    @Test
    fun `태그별 포스트 조회 - 0 이하 page·limit은 기본값으로 보정`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val tag = query { tagRepository.createTag(creator.id.value, "공지", TagTargetType.POST) }

            val response = postService.getPostsByTag(tag.id.value, currentUserId = null, page = 0, limit = 0)

            assertEquals(1, response.page)
        }
    }

    @Test
    fun `관리자 글 목록 - 0 이하 limit은 기본 개수로 보정`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            repeat(3) { TestFixtures.createTestPost(userId = user.id.value) }

            val response = postService.getAdminPosts(page = 1, limit = 0)

            assertEquals(3, response.items.size)
        }
    }

    // ===== 포스트 수정 테스트 =====

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
            assertFailsWith<ForbiddenException> {
                postService.updatePost(post.id.value, otherUser.id.value, request)
            }
        }
    }

    // ===== 포스트 삭제 테스트 =====

    @Test
    fun `글을 지우면 그 글의 새 글 알림을 정리한다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            postService.deletePost(post.id.value, user.id.value)

            coVerify { notificationService.deactivateNewPostNotifications(post.id.value) }
            assertFailsWith<PostNotFoundException> {
                postService.getPostById(post.id.value, user.id.value)
            }
        }
    }

    @Test
    fun `블라인드된 내 글은 작성자가 삭제할 수 있다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            query {
                PostDao.findById(post.id.value)!!.apply {
                    isActive = false
                    isBlinded = true
                }
            }

            postService.deletePost(post.id.value, user.id.value)

            val isBlinded = query { PostDao.findById(post.id.value)!!.isBlinded }
            assertFalse(isBlinded)
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
            assertFailsWith<ForbiddenException> {
                postService.deletePost(post.id.value, otherUser.id.value)
            }
        }
    }

    @Test
    fun `고정 글을 지우면 피드 고정 한도가 돌아온다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val pinned = List(Constants.Social.MAX_PINNED_POSTS) {
                TestFixtures.createTestPost(userId = creator.id.value, creatorId = creator.id.value, contextType = PostContextType.CREATOR_FEED)
            }
            pinned.forEach { postService.togglePinPostInFeed(it.id.value, creator.id.value) }

            postService.deletePost(pinned.first().id.value, creator.id.value)

            val next = TestFixtures.createTestPost(userId = creator.id.value, creatorId = creator.id.value, contextType = PostContextType.CREATOR_FEED)
            assertEquals(true, postService.togglePinPostInFeed(next.id.value, creator.id.value).isPinned)
        }
    }

    @Test
    fun `피드 고정 토글은 고정 여부가 담긴 포스트 상세 캐시를 지운다`() {
        runBlocking {
            clearMocks(cacheService, answers = false)
            val creator = TestFixtures.createTestCreator()
            val post = TestFixtures.createTestPost(userId = creator.id.value, creatorId = creator.id.value, contextType = PostContextType.CREATOR_FEED)

            postService.togglePinPostInFeed(post.id.value, creator.id.value)

            coVerify(exactly = 1) { cacheService.deletePattern(CacheKeys.Patterns.post(post.id.value)) }
        }
    }

    @Test
    fun `피드 고정 실패 - 크리에이터 피드 글이 아님`() {
        runBlocking {
            // Given - 크리에이터 본인 글이지만 커뮤니티 글
            val creator = TestFixtures.createTestCreator()
            val post = TestFixtures.createTestPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                contextType = PostContextType.COMMUNITY
            )

            // When & Then
            assertFailsWith<ForbiddenException> {
                postService.togglePinPostInFeed(post.id.value, creator.id.value)
            }
        }
    }

    // ===== 비밀글 숨김 판정 =====

    @Test
    fun `비밀글 숨김 판정 - 삭제된 비밀글도 비참여자에게는 숨김`() {
        runBlocking {
            // Given
            val owner = TestFixtures.createTestUser()
            val outsider = TestFixtures.createTestUser(email = "outsider@test.com", username = "outsider")
            val secretPost = TestFixtures.createTestPost(userId = owner.id.value)
            query { PostDao.findById(secretPost.id.value)!!.apply { isSecret = true; isActive = false } }
            val openPost = TestFixtures.createTestPost(userId = owner.id.value)

            // When & Then
            assertTrue(query { postService.isSecretPostHiddenFrom(secretPost.id.value, null) })
            assertTrue(query { postService.isSecretPostHiddenFrom(secretPost.id.value, outsider.id.value) })
            assertFalse(query { postService.isSecretPostHiddenFrom(secretPost.id.value, owner.id.value) })
            assertFalse(query { postService.isSecretPostHiddenFrom(openPost.id.value, null) })
        }
    }

    // ===== 크리에이터 해제 숨김 글 조회 =====

    @Test
    fun `포스트 조회 - 크리에이터 해제로 숨겨진 글은 유효하지 않은 포스트 404`() {
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
            assertEquals("유효하지 않은 포스트입니다.", exception.message)

            // 일반 삭제 글은 기존 404 유지
            val deletedPost = TestFixtures.createTestPost(userId = creator.id.value)
            query { postRepository.deletePost(deletedPost.id.value) }
            assertFailsWith<PostNotFoundException> {
                postService.getPostById(deletedPost.id.value, null)
            }
        }
    }

    // ===== 미디어 직접 업로드 테스트 =====

    @Test
    fun `포스트 수정 실패 - 다른 포스트에 발급된 업로드 ID`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val otherPost = TestFixtures.createTestPost(userId = user.id.value)
            val upload = prepareVideo(otherPost.id.value, user.id.value)

            // When & Then
            assertFailsWith<NotFoundException> {
                attachByUpdate(post.id.value, user.id.value, items = listOf(MediaItemRef(uploadId = upload.uploadId)))
            }
        }
    }

    @Test
    fun `포스트 수정 실패 - 이미 사용한 업로드 ID 재사용`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val upload = prepareVideo(post.id.value, user.id.value)
            val items = listOf(MediaItemRef(uploadId = upload.uploadId))
            val first = attachByUpdate(post.id.value, user.id.value, items = items)

            // When & Then
            assertFailsWith<ConflictException> {
                attachByUpdate(post.id.value, user.id.value, items = items, keepMediaIds = first.mediaItems.map { it.id })
            }
        }
    }

    @Test
    fun `포스트 수정 실패 - 처리 중 실패하면 먼저 올린 사진만 바로 회수`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val upload = prepareVideo(post.id.value, user.id.value)
            coEvery { fileUploadService.downloadToTempFile(any(), any(), any()) } throws IllegalStateException("다운로드 실패")

            // When
            assertFailsWith<IllegalStateException> {
                attachByUpdate(
                    post.id.value, user.id.value,
                    files = listOf(byteArrayOf(1)),
                    items = listOf(MediaItemRef(fileIndex = 0), MediaItemRef(uploadId = upload.uploadId))
                )
            }

            // Then - 영상 원본은 미사용 기록으로 남아 정리 스케줄러가 지움
            coVerify {
                fileUploadService.deleteByUrls(
                    match { urls ->
                        imageUrl(post.id.value, 1) in urls && urls.none { it.contains("/post_${user.id.value}_") }
                    }
                )
            }
            assertEquals(UploadStatus.ISSUED, query { MediaUploadDao.findById(upload.uploadId)?.status })
            assertEquals(0L, query { PostMediaDao.find { PostMediaTable.postId eq post.id.value }.count() })
        }
    }

    @Test
    fun `포스트 수정 실패 - 처리하는 사이 포스트가 지워지면 붙이지 않음`() {
        runBlocking {
            // Given - 영상을 받는 동안 작성자가 포스트 삭제
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val upload = prepareVideo(post.id.value, user.id.value)
            coEvery { fileUploadService.downloadToTempFile(any(), any(), any()) } coAnswers {
                query { postRepository.deletePost(post.id.value) }
                File.createTempFile("test_src", ".tmp").also { downloadedFiles += it }
            }

            // When & Then
            assertFailsWith<PostNotFoundException> {
                attachByUpdate(post.id.value, user.id.value, items = listOf(MediaItemRef(uploadId = upload.uploadId)))
            }
            assertEquals(0L, query { PostMediaDao.find { PostMediaTable.postId eq post.id.value }.count() })
            assertEquals(UploadStatus.ISSUED, query { MediaUploadDao.findById(upload.uploadId)?.status })
            assertTrue(downloadedFiles.isNotEmpty() && downloadedFiles.none { it.exists() })
        }
    }

    @Test
    fun `포스트 수정 실패 - 영상 가공 중 실패하면 받아 둔 임시파일 회수`() {
        runBlocking {
            // Given - 영상을 받은 뒤 잠금 미리보기 생성에서 실패
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val upload = prepareVideo(post.id.value, user.id.value)
            coEvery { videoProcessingService.extractThumbnail(any<File>()) } returns byteArrayOf(1)
            coEvery { imageProcessingService.generateLockedPreview(any()) } throws IllegalStateException("미리보기 실패")

            try {
                // When & Then
                assertFailsWith<IllegalStateException> {
                    attachByUpdate(post.id.value, user.id.value, items = listOf(MediaItemRef(uploadId = upload.uploadId)))
                }
                assertTrue(downloadedFiles.isNotEmpty() && downloadedFiles.none { it.exists() })
            } finally {
                coEvery { videoProcessingService.extractThumbnail(any<File>()) } returns null
                coEvery { imageProcessingService.generateLockedPreview(any()) } returns null
            }
        }
    }

    @Test
    fun `포스트 수정 - 새 항목은 남긴 미디어 뒤에 items 순서대로 붙음`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)
            val existing = attachByUpdate(post.id.value, user.id.value, files = listOf(byteArrayOf(1))).mediaItems.single()
            val upload = prepareVideo(post.id.value, user.id.value)

            // When
            val response = attachByUpdate(
                post.id.value, user.id.value,
                files = listOf(byteArrayOf(1, 2)),
                items = listOf(MediaItemRef(uploadId = upload.uploadId), MediaItemRef(fileIndex = 0)),
                keepMediaIds = listOf(existing.id)
            )

            // Then
            assertEquals(existing.id, response.mediaItems.first().id)
            assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.IMAGE), response.mediaItems.map { it.type })
            assertEquals(imageUrl(post.id.value, 2), response.mediaItems.last().url)
            assertEquals(UploadStatus.CONSUMED, query { MediaUploadDao.findById(upload.uploadId)?.status })
            // 백그라운드 재인코딩이 끝나면 임시파일 삭제
            assertTrue(downloadedFiles.isNotEmpty() && downloadedFiles.none { it.exists() })
        }
    }

    @Test
    fun `포스트 수정 실패 - 영상 파일 파트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When & Then - 영상은 직접 업로드로만
            assertFailsWith<InvalidInputException> {
                attachByUpdate(post.id.value, user.id.value, files = listOf(byteArrayOf(1)), contentTypes = listOf("video/mp4"))
            }
        }
    }

    @Test
    fun `포스트 수정 실패 - items가 파일 파트를 빠뜨림`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = user.id.value)

            // When & Then
            assertFailsWith<InvalidInputException> {
                attachByUpdate(
                    post.id.value, user.id.value,
                    files = listOf(byteArrayOf(1), byteArrayOf(1, 2)),
                    items = listOf(MediaItemRef(fileIndex = 0))
                )
            }
        }
    }

    /** 수정 요청으로 미디어 붙이기, 남길 미디어를 안 주면 기존 미디어는 지움 */
    private suspend fun attachByUpdate(
        postId: Int,
        userId: Int,
        files: List<ByteArray> = emptyList(),
        contentTypes: List<String> = List(files.size) { "image/jpeg" },
        items: List<MediaItemRef>? = null,
        keepMediaIds: List<Int>? = null
    ) = postMediaService.updatePostWithMedia(
        postId = postId,
        userId = userId,
        content = null,
        keepMediaIds = keepMediaIds,
        newFiles = files,
        newContentTypes = contentTypes,
        reorder = null,
        items = items
    )

    private suspend fun prepareVideo(postId: Int, userId: Int) = postMediaService.preparePostVideoUpload(
        postId = postId,
        userId = userId,
        request = VideoUploadPrepareRequest(mimeType = "video/mp4", size = 1000)
    )

    private fun imageUrl(postId: Int, size: Int) =
        "https://test.supabase.co/storage/v1/object/public/posts/posts/$postId/$size.jpg"
}
