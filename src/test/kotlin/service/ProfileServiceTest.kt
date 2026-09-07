package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.core.storage.AvatarUploadResult
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.features.user.domain.ProfileService
import com.ninezero.features.user.presentation.models.request.UpdateProfileRequest
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * ProfileService 테스트
 *
 * 테스트 케이스: 13개
 * - 프로필 조회: 4개
 * - 프로필 수정: 4개
 * - 아바타 업로드: 3개
 * - 프로필 통계: 2개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileServiceTest {

    private lateinit var profileService: ProfileService
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var followRepository: FollowRepositoryImpl
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var planRepository: SubscriptionPlanRepositoryImpl
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var blockedUserRepository: BlockedUserRepositoryImpl
    private lateinit var fileUploadService: FileUploadService
    private lateinit var imageProcessingService: ImageProcessingService
    private lateinit var cacheService: CacheService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            userRepository = UserRepositoryImpl()
            followRepository = FollowRepositoryImpl()
            postRepository = PostRepositoryImpl()
            planRepository = SubscriptionPlanRepositoryImpl()
            subscriptionRepository = SubscriptionRepositoryImpl()
            blockedUserRepository = BlockedUserRepositoryImpl()

            // Mock 객체 생성
            fileUploadService = mockk(relaxed = true)
            imageProcessingService = mockk(relaxed = true)
            cacheService = mockk(relaxed = true)

            // Mock 동작 정의
            coEvery {
                imageProcessingService.validateImage(any(), any())
            } returns true

            coEvery {
                imageProcessingService.getFileExtension(any())
            } returns "jpg"

            coEvery {
                fileUploadService.uploadAvatar(any(), any(), any())
            } returns AvatarUploadResult(
                avatarUrl = "https://example.com/avatar.jpg",
                avatarThumbUrl = "https://example.com/avatar_thumb.jpg"
            )

            coEvery {
                fileUploadService.deleteFileIfSupabase(any())
            } returns true

            // CacheService mock 설정
            coEvery { cacheService.get<Any>(any(), any()) } returns null

            profileService = ProfileService(
                userRepository = userRepository,
                followRepository = followRepository,
                postRepository = postRepository,
                subscriptionPlanRepository = planRepository,
                subscriptionRepository = subscriptionRepository,
                blockedUserRepository = blockedUserRepository,
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

    // ===== 프로필 조회 테스트 (4개) =====

    @Test
    fun `프로필 조회 성공 - 기본 프로필`() {
        runBlocking {
            val user = TestFixtures.createTestUser(
                email = "test@example.com",
                username = "testuser"
            )

            val response = profileService.getUserProfile(user.id.value)

            assertNotNull(response)
            assertEquals(user.id.value, response.userId)
            assertEquals("testuser", response.displayName)
            assertEquals(0, response.followerCount)
            assertEquals(0, response.followingCount)
            assertEquals(0, response.postCount)
        }
    }

    @Test
    fun `프로필 조회 성공 - 크리에이터 프로필`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator(
                email = "creator@example.com",
                username = "creator"
            )

            val response = profileService.getUserProfile(creator.id.value)

            assertNotNull(response)
            assertEquals(creator.id.value, response.userId)
            assertEquals(UserRole.CREATOR, response.role)
            assertNotNull(response.subscriberCount)
        }
    }

    @Test
    fun `프로필 조회 실패 - 존재하지 않는 사용자`() {
        runBlocking {
            assertFailsWith<UserNotFoundException> {
                profileService.getUserProfile(99999)
            }
        }
    }

    @Test
    fun `프로필 조회 실패 - 탈퇴한 사용자`() {
        runBlocking {
            val user = TestFixtures.createTestUser(email = "deleted@example.com")
            query { userRepository.findUserById(user.id.value)!!.isActive = false }

            val exception = assertFailsWith<NotFoundException> {
                profileService.getUserProfile(user.id.value)
            }
            assertEquals("탈퇴한 사용자입니다.", exception.message)
        }
    }

    @Test
    fun `프로필 조회 - 일반 사용자는 subscriberCount null`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            val response = profileService.getUserProfile(user.id.value)

            assertNotNull(response)
            assertEquals(null, response.subscriberCount)
        }
    }

    // ===== 프로필 수정 테스트 (4개) =====

    @Test
    fun `프로필 수정 성공 - 표시 이름 변경`() {
        runBlocking {
            val user = TestFixtures.createTestUser(
                email = "test@example.com",
                username = "testuser"
            )

            val request = UpdateProfileRequest(
                displayName = "새로운 표시 이름"
            )

            val response = profileService.updateProfile(user.id.value, request)

            assertNotNull(response)
            assertEquals("새로운 표시 이름", response.displayName)
        }
    }

    @Test
    fun `프로필 수정 성공 - 자기소개 추가`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val request = UpdateProfileRequest(
                bio = "안녕하세요. 테스트 사용자입니다."
            )

            val response = profileService.updateProfile(user.id.value, request)

            assertNotNull(response)
            assertEquals("안녕하세요. 테스트 사용자입니다.", response.bio)
        }
    }

    @Test
    fun `프로필 수정 성공 - 여러 필드 동시 변경`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val request = UpdateProfileRequest(
                displayName = "새 이름",
                bio = "새 소개",
                location = "서울",
                website = "https://example.com"
            )

            val response = profileService.updateProfile(user.id.value, request)

            assertNotNull(response)
            assertEquals("새 이름", response.displayName)
            assertEquals("새 소개", response.bio)
            assertEquals("서울", response.location)
            assertEquals("https://example.com", response.website)
        }
    }

    @Test
    fun `프로필 수정 실패 - 존재하지 않는 사용자`() {
        runBlocking {
            val request = UpdateProfileRequest(
                displayName = "새 이름"
            )

            assertFailsWith<UserNotFoundException> {
                profileService.updateProfile(99999, request)
            }
        }
    }

    // ===== 아바타 업로드 테스트 (3개) =====

    @Test
    fun `아바타 업로드 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val imageData = ByteArray(1024) { 0 }
            val contentType = "image/jpeg"

            val response = profileService.uploadAvatar(user.id.value, imageData, contentType)

            assertNotNull(response)
            assertNotNull(response.avatarUrl)
            assertEquals(response.avatarUrl.contains("avatar.jpg"), true)
        }
    }

    @Test
    fun `아바타 업로드 - 기존 아바타 교체`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            // 첫 번째 업로드
            val imageData1 = ByteArray(1024) { 0 }
            profileService.uploadAvatar(user.id.value, imageData1, "image/jpeg")

            // Mock URL 변경
            coEvery {
                fileUploadService.uploadAvatar(any(), any(), any())
            } returns AvatarUploadResult(
                avatarUrl = "https://example.com/new-avatar.jpg",
                avatarThumbUrl = "https://example.com/new-avatar_thumb.jpg"
            )

            // 두 번째 업로드 (교체)
            val imageData2 = ByteArray(1024) { 1 }
            val response = profileService.uploadAvatar(user.id.value, imageData2, "image/jpeg")

            assertNotNull(response)
            assertNotNull(response.avatarUrl)
            assertEquals(response.avatarUrl.contains("new-avatar.jpg"), true)
        }
    }

    @Test
    fun `아바타 업로드 실패 - 존재하지 않는 사용자`() {
        runBlocking {
            val imageData = ByteArray(1024) { 0 }

            assertFailsWith<UserNotFoundException> {
                profileService.uploadAvatar(99999, imageData, "image/jpeg")
            }
        }
    }

    // ===== 프로필 통계 테스트 (2개) =====

    @Test
    fun `프로필 통계 - 팔로워 수 확인`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")

            // user2, user3가 user1을 팔로우
            query {
                followRepository.createFollow(user2.id.value, user1.id.value)
                followRepository.createFollow(user3.id.value, user1.id.value)
            }

            val response = profileService.getUserProfile(user1.id.value)

            assertNotNull(response)
            assertEquals(2, response.followerCount)
            assertEquals(0, response.followingCount)
        }
    }

    @Test
    fun `프로필 통계 - 게시글 수 확인`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            // 게시글 3개 작성
            TestFixtures.createTestPosts(user.id.value, 3)

            val response = profileService.getUserProfile(user.id.value)

            assertNotNull(response)
            assertEquals(3, response.postCount)
        }
    }
}