package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.InvalidDisplayNameException
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
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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

        // 테스트마다 업로드 스텁·호출 기록 초기화
        clearMocks(fileUploadService)
        coEvery {
            fileUploadService.uploadAvatar(any(), any(), any())
        } returns AvatarUploadResult(
            avatarUrl = "https://example.com/avatar.jpg",
            avatarThumbUrl = "https://example.com/avatar_thumb.jpg"
        )
        coEvery {
            fileUploadService.deleteByUrl(any())
        } returns true
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    // ===== 프로필 조회 테스트 =====

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

    // ===== 프로필 수정 테스트 =====

    @Test
    fun `프로필 수정 - 빈 값을 보내면 소개·위치·웹사이트를 지운다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            profileService.updateProfile(
                user.id.value,
                UpdateProfileRequest(bio = "소개", location = "서울", website = "https://example.com")
            )

            val response = profileService.updateProfile(
                user.id.value,
                UpdateProfileRequest(bio = "", location = "  ", website = "")
            )

            assertNull(response.bio)
            assertNull(response.location)
            assertNull(response.website)
        }
    }

    @Test
    fun `프로필 수정 - 보내지 않은 필드는 그대로 둔다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            profileService.updateProfile(
                user.id.value,
                UpdateProfileRequest(bio = "소개", website = "https://example.com")
            )

            val response = profileService.updateProfile(user.id.value, UpdateProfileRequest(displayName = "새 이름"))

            assertEquals("소개", response.bio)
            assertEquals("https://example.com", response.website)
        }
    }

    @Test
    fun `프로필 수정 실패 - 공백만 있는 표시 이름`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            assertFailsWith<InvalidDisplayNameException> {
                profileService.updateProfile(user.id.value, UpdateProfileRequest(displayName = "   "))
            }
        }
    }

    // ===== 아바타 업로드 테스트 =====

    @Test
    fun `아바타 업로드 - 기존 아바타 교체`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            // 첫 번째 업로드
            val imageData1 = ByteArray(1024) { 0 }
            profileService.uploadAvatar(user.id.value, imageData1)

            coEvery {
                fileUploadService.uploadAvatar(any(), any(), any())
            } returns AvatarUploadResult(
                avatarUrl = "https://example.com/new-avatar.jpg",
                avatarThumbUrl = "https://example.com/new-avatar_thumb.jpg"
            )

            // 두 번째 업로드로 교체
            val imageData2 = ByteArray(1024) { 1 }
            val response = profileService.uploadAvatar(user.id.value, imageData2)

            assertEquals("https://example.com/new-avatar.jpg", response.avatarUrl)
            // DB 반영 뒤 옛 아바타·썸네일만 삭제
            coVerify(exactly = 1) { fileUploadService.deleteByUrl("https://example.com/avatar.jpg") }
            coVerify(exactly = 1) { fileUploadService.deleteByUrl("https://example.com/avatar_thumb.jpg") }
            coVerify(exactly = 0) { fileUploadService.deleteByUrl("https://example.com/new-avatar.jpg") }
            coVerify(exactly = 0) { fileUploadService.deleteByUrl("https://example.com/new-avatar_thumb.jpg") }
        }
    }

    // ===== 프로필 통계 테스트 =====

    @Test
    fun `프로필 통계 - 플랜이 모두 비활성이면 멤버십 플랜 없음`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            query {
                val plan = planRepository.createPlan(
                    creatorId = creator.id.value, name = "기본", tier = SubscriptionPlanTier.TIER1,
                    description = "설명", price = BigDecimal("5000"), benefits = "[\"혜택\"]"
                )
                planRepository.updatePlan(plan.id.value, name = null, description = null, price = null, benefits = null, isActive = false)
            }

            val response = profileService.getUserProfile(creator.id.value)

            assertEquals(false, response.hasSubscriptionPlans)
        }
    }
}
