package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.CreatorApplicationStatus
import com.ninezero.core.common.config.MediaType
import com.ninezero.core.common.config.NotificationType
import com.ninezero.core.common.config.PointType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.SocialProvider
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.SubscriptionStatus
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserProfileDao
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.email.EmailService
import com.ninezero.core.storage.FileUploadService
import com.ninezero.features.commerce.data.BillingKeyRepositoryImpl
import com.ninezero.features.commerce.data.CartRepositoryImpl
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.data.WishlistRepositoryImpl
import com.ninezero.features.coupon.data.CouponRepositoryImpl
import com.ninezero.features.coupon.data.UserCouponRepositoryImpl
import com.ninezero.features.notification.data.NotificationRepositoryImpl
import com.ninezero.features.point.data.PointHistoryRepositoryImpl
import com.ninezero.features.point.data.PointRepositoryImpl
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.search.data.SearchHistoryRepositoryImpl
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.LikeRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.user.data.AddressRepositoryImpl
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.CreatorApplicationRepositoryImpl
import com.ninezero.features.user.data.RefreshTokenRepositoryImpl
import com.ninezero.features.user.data.SocialAccountRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.features.user.data.UserSessionRepositoryImpl
import com.ninezero.features.user.domain.AccountCleanupService
import com.ninezero.features.user.domain.AuthService
import com.ninezero.features.user.presentation.models.request.ChangePasswordRequest
import com.ninezero.features.user.presentation.models.request.LoginRequest
import com.ninezero.features.user.presentation.models.request.RegisterRequest
import com.ninezero.features.user.presentation.models.response.AuthResponse
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.plusDays
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.mockk
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 계정 관리(비밀번호 변경/탈퇴) 테스트
 *
 * 테스트 케이스: 12개
 * - 비밀번호 변경: 4개
 * - 계정 탈퇴(즉시 익명화): 4개
 * - 계정 탈퇴 정리(구독/빌링키/개인 데이터/크리에이터 유산/신청): 4개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountManagementTest {
    private lateinit var authService: AuthService
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var refreshTokenRepository: RefreshTokenRepositoryImpl
    private lateinit var userSessionRepository: UserSessionRepositoryImpl
    private lateinit var socialAccountRepository: SocialAccountRepositoryImpl
    private lateinit var fileUploadService: FileUploadService

    // 탈퇴 정리 검증용
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var planRepository: SubscriptionPlanRepositoryImpl
    private lateinit var billingKeyRepository: BillingKeyRepositoryImpl
    private lateinit var productRepository: ProductRepositoryImpl
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var creatorApplicationRepository: CreatorApplicationRepositoryImpl
    private lateinit var addressRepository: AddressRepositoryImpl
    private lateinit var searchHistoryRepository: SearchHistoryRepositoryImpl
    private lateinit var cartRepository: CartRepositoryImpl
    private lateinit var couponRepository: CouponRepositoryImpl
    private lateinit var userCouponRepository: UserCouponRepositoryImpl
    private lateinit var notificationRepository: NotificationRepositoryImpl
    private lateinit var blockedUserRepository: BlockedUserRepositoryImpl
    private lateinit var wishlistRepository: WishlistRepositoryImpl
    private lateinit var followRepository: FollowRepositoryImpl
    private lateinit var pointRepository: PointRepositoryImpl
    private lateinit var pointHistoryRepository: PointHistoryRepositoryImpl
    private lateinit var likeRepository: LikeRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            userRepository = UserRepositoryImpl()
            refreshTokenRepository = RefreshTokenRepositoryImpl()
            userSessionRepository = UserSessionRepositoryImpl()
            socialAccountRepository = SocialAccountRepositoryImpl()
            fileUploadService = mockk(relaxed = true)

            subscriptionRepository = SubscriptionRepositoryImpl()
            planRepository = SubscriptionPlanRepositoryImpl()
            billingKeyRepository = BillingKeyRepositoryImpl()
            productRepository = ProductRepositoryImpl()
            postRepository = PostRepositoryImpl()
            creatorApplicationRepository = CreatorApplicationRepositoryImpl()
            addressRepository = AddressRepositoryImpl()
            searchHistoryRepository = SearchHistoryRepositoryImpl()
            cartRepository = CartRepositoryImpl()
            couponRepository = CouponRepositoryImpl()
            userCouponRepository = UserCouponRepositoryImpl()
            notificationRepository = NotificationRepositoryImpl()
            blockedUserRepository = BlockedUserRepositoryImpl()
            wishlistRepository = WishlistRepositoryImpl()
            followRepository = FollowRepositoryImpl()
            pointRepository = PointRepositoryImpl()
            pointHistoryRepository = PointHistoryRepositoryImpl()
            likeRepository = LikeRepositoryImpl()

            val pointService = PointService(
                pointRepository = pointRepository,
                pointHistoryRepository = pointHistoryRepository,
                notificationService = mockk(relaxed = true),
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            )

            val accountCleanupService = AccountCleanupService(
                subscriptionRepository = subscriptionRepository,
                billingKeyRepository = billingKeyRepository,
                productRepository = productRepository,
                postRepository = postRepository,
                creatorApplicationRepository = creatorApplicationRepository,
                addressRepository = addressRepository,
                searchHistoryRepository = searchHistoryRepository,
                cartRepository = cartRepository,
                userCouponRepository = userCouponRepository,
                notificationRepository = notificationRepository,
                blockedUserRepository = blockedUserRepository,
                wishlistRepository = wishlistRepository,
                followRepository = followRepository,
                pointRepository = pointRepository,
                pointHistoryRepository = pointHistoryRepository
            )

            authService = AuthService(
                userRepository = userRepository,
                userSessionRepository = userSessionRepository,
                refreshTokenRepository = refreshTokenRepository,
                emailService = mockk<EmailService>(relaxed = true),
                pointService = pointService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
                socialAccountRepository = socialAccountRepository,
                socialTokenVerifiers = emptyList(),
                fileUploadService = fileUploadService,
                cacheService = CacheService(),
                accountCleanupService = accountCleanupService,
                webSocketManager = mockk(relaxed = true)
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()
            // 파일 수거 호출 기록 초기화 — 테스트 간 coVerify 오염 방지(스텁 유지)
            clearMocks(fileUploadService, answers = false)
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    private suspend fun login(email: String, password: String, deviceId: String = "device-1"): AuthResponse =
        authService.login(
            LoginRequest(email = email, password = password, deviceId = deviceId),
            "TestAgent",
            "127.0.0.1"
        )

    private suspend fun sessionIdOf(refreshToken: String): String = query {
        refreshTokenRepository.findByToken(refreshToken)!!.sessionId
    }

    // ===== 비밀번호 변경 =====

    @Test
    fun `비밀번호 변경 성공 - 새 비밀번호로만 로그인 가능`() {
        runBlocking {
            val user = TestFixtures.createTestUser(email = "user@example.com", password = "OldPass123!")
            val auth = login("user@example.com", "OldPass123!")
            val sessionId = sessionIdOf(auth.refreshToken)

            authService.changePassword(
                userId = user.id.value,
                sessionId = sessionId,
                request = ChangePasswordRequest(currentPassword = "OldPass123!", newPassword = "NewPass456!")
            )

            // 새 비밀번호 로그인 성공
            assertNotNull(login("user@example.com", "NewPass456!", deviceId = "device-2"))

            // 옛 비밀번호 로그인 실패
            assertFailsWith<InvalidCredentialsException> {
                login("user@example.com", "OldPass123!", deviceId = "device-3")
            }
        }
    }

    @Test
    fun `비밀번호 변경 실패 - 현재 비밀번호 불일치`() {
        runBlocking {
            val user = TestFixtures.createTestUser(email = "user@example.com", password = "OldPass123!")

            assertFailsWith<InvalidCredentialsException> {
                authService.changePassword(
                    userId = user.id.value,
                    sessionId = null,
                    request = ChangePasswordRequest(currentPassword = "WrongPass!", newPassword = "NewPass456!")
                )
            }
        }
    }

    @Test
    fun `비밀번호 변경 실패 - 현재 비밀번호와 동일`() {
        runBlocking {
            val user = TestFixtures.createTestUser(email = "user@example.com", password = "OldPass123!")
            val authB = login("user@example.com", "OldPass123!", deviceId = "device-B")

            assertFailsWith<InvalidPasswordException> {
                authService.changePassword(
                    userId = user.id.value,
                    sessionId = null,
                    request = ChangePasswordRequest(currentPassword = "OldPass123!", newPassword = "OldPass123!")
                )
            }

            // 거절됐으므로 기존 비밀번호는 그대로 살아 있고, 타 기기 세션도 끊기지 않는다
            login("user@example.com", "OldPass123!", deviceId = "device-C")
            assertNotNull(authService.refreshAccessToken(authB.refreshToken))
        }
    }

    @Test
    fun `비밀번호 변경 - 다른 기기 세션은 로그아웃, 현재 세션은 유지`() {
        runBlocking {
            val user = TestFixtures.createTestUser(email = "user@example.com", password = "OldPass123!")
            val authA = login("user@example.com", "OldPass123!", deviceId = "device-A")
            val authB = login("user@example.com", "OldPass123!", deviceId = "device-B")
            val sessionA = sessionIdOf(authA.refreshToken)

            authService.changePassword(
                userId = user.id.value,
                sessionId = sessionA,
                request = ChangePasswordRequest(currentPassword = "OldPass123!", newPassword = "NewPass456!")
            )

            // 다른 기기(B)의 refresh token은 무효화
            assertFailsWith<TokenExpiredException> {
                authService.refreshAccessToken(authB.refreshToken)
            }

            // 현재 기기(A)는 정상 갱신
            assertNotNull(authService.refreshAccessToken(authA.refreshToken))
        }
    }

    @Test
    fun `비밀번호 변경 실패 - 간편로그인 전용 계정`() {
        runBlocking {
            val socialUser = query {
                UserDao.new {
                    this.email = "social@example.com"
                    this.username = "socialuser"
                    this.passwordHash = null
                    this.isActive = true
                }
            }

            assertFailsWith<SocialOnlyAccountException> {
                authService.changePassword(
                    userId = socialUser.id.value,
                    sessionId = null,
                    request = ChangePasswordRequest(currentPassword = "whatever", newPassword = "NewPass456!")
                )
            }
        }
    }

    // ===== 계정 탈퇴 (즉시 익명화) =====

    @Test
    fun `계정 탈퇴 - 개인정보 익명화 및 재로그인 불가`() {
        runBlocking {
            val user = TestFixtures.createTestUser(email = "user@example.com", password = "Pass1234!")
            val userId = user.id.value
            query {
                UserProfileDao.new {
                    this.userId = user.id
                    this.displayName = "홍길동"
                    this.bio = "소개글"
                    this.avatarUrl = "https://cdn.example.com/avatars/users/$userId/a.webp"
                }
            }
            val auth = login("user@example.com", "Pass1234!")

            authService.deleteAccount(userId)

            query {
                val deleted = userRepository.findUserById(userId)!!
                assertTrue(deleted.email.startsWith("deleted_"), "이메일이 익명화되어야 한다")
                assertTrue(deleted.username.startsWith("deleted"), "username이 익명화되어야 한다")
                assertNull(deleted.passwordHash)
                assertTrue(!deleted.isActive)

                val profile = UserProfileDao.find { UserProfileTable.userId eq userId }.first()
                assertEquals(Constants.User.DELETED_USER_DISPLAY_NAME, profile.displayName)
                assertNull(profile.avatarUrl)
                assertNull(profile.bio)
            }

            // 옛 이메일로 로그인 불가 (유저 조회 실패)
            assertFailsWith<InvalidCredentialsException> {
                login("user@example.com", "Pass1234!", deviceId = "device-2")
            }

            // 기존 refresh token 무효화
            assertFailsWith<TokenExpiredException> {
                authService.refreshAccessToken(auth.refreshToken)
            }
        }
    }

    @Test
    fun `계정 탈퇴 실패 - 크리에이터 계정`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            assertFailsWith<ForbiddenException> {
                authService.deleteAccount(creator.id.value)
            }
        }
    }

    @Test
    fun `계정 탈퇴 - 소셜 연결 삭제로 같은 소셜 재가입 가능`() {
        runBlocking {
            val user = TestFixtures.createTestUser(email = "social@example.com")
            val userId = user.id.value
            query {
                socialAccountRepository.create(userId, SocialProvider.GOOGLE, "google-sub-1")
            }

            authService.deleteAccount(userId)

            val mapped = query {
                socialAccountRepository.findUserIdByProvider(SocialProvider.GOOGLE, "google-sub-1")
            }
            assertNull(mapped, "소셜 연결이 삭제되어 같은 구글 계정으로 재가입 가능해야 한다")
        }
    }

    @Test
    fun `계정 탈퇴 - 같은 이메일로 재가입 가능`() {
        runBlocking {
            val user = TestFixtures.createTestUser(email = "user@example.com")
            authService.deleteAccount(user.id.value)

            val response = authService.register(
                RegisterRequest(email = "user@example.com", username = "newbie123", password = "Pass1234!")
            )
            assertEquals("user@example.com", response.email)
        }
    }

    // ===== 계정 탈퇴 정리 =====

    @Test
    fun `계정 탈퇴 - 활성 구독 즉시 만료 및 빌링키 폐기`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()

            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5900"),
                    benefits = "[\"혜택\"]"
                )
            }
            val subscription = query {
                subscriptionRepository.createSubscription(
                    userId = user.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().plusDays(30),
                    autoRenew = true
                )
            }
            query {
                billingKeyRepository.create(
                    userId = user.id.value,
                    customerKey = "ck-1",
                    billingKey = "bk-1",
                    cardCompany = null,
                    cardNumberMasked = null,
                    cardType = null,
                    ownerType = null,
                    authenticatedAt = null
                )
                assertEquals(1, subscriptionRepository.countCreatorSubscribers(creator.id.value))
            }

            authService.deleteAccount(user.id.value)

            query {
                assertEquals(
                    SubscriptionStatus.EXPIRED,
                    subscriptionRepository.findSubscriptionById(subscription.id.value)?.status
                )
                // 유령 구독이 사라져 크리에이터 해제 조건(구독자 0)이 즉시 풀린다
                assertEquals(0, subscriptionRepository.countCreatorSubscribers(creator.id.value))
                assertNull(billingKeyRepository.findActiveByUserId(user.id.value))
            }
        }
    }

    @Test
    fun `계정 탈퇴 - 개인 데이터 전부 정리, 좋아요는 잔존`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val other = TestFixtures.createTestUser(email = "other@example.com", username = "other")
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)
            val likedPost = TestFixtures.createTestPost(userId = other.id.value)

            query {
                addressRepository.createAddress(
                    userId = user.id.value,
                    recipientName = "홍길동",
                    recipientPhone = "010-1234-5678",
                    zipCode = "12345",
                    address = "서울시 강남구",
                    addressDetail = null,
                    memo = null,
                    isDefault = true
                )
                searchHistoryRepository.createHistory(user.id.value, "카나타", "CREATOR", 3)
                cartRepository.createCartItem(user.id.value, product.id.value, 1)

                val coupon = couponRepository.create(
                    creatorId = creator.id.value,
                    code = "DELETE123456",
                    name = "탈퇴 테스트 쿠폰",
                    description = null,
                    type = CouponType.FIXED_AMOUNT,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = BigDecimal("1000"),
                    minOrderAmount = BigDecimal("10000"),
                    maxDiscountAmount = null,
                    totalQuantity = 10,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = nowUtc(),
                    endDate = nowUtc().plusDays(30)
                )
                userCouponRepository.create(user.id.value, coupon.id.value, nowUtc().plusDays(30))

                notificationRepository.createNotification(
                    recipientId = user.id.value,
                    type = NotificationType.SYSTEM_ANNOUNCEMENT,
                    title = "공지",
                    message = "테스트 알림"
                )

                blockedUserRepository.createBlock(user.id.value, other.id.value)
                blockedUserRepository.createBlock(other.id.value, user.id.value)

                wishlistRepository.toggleWishlist(user.id.value, product.id.value)

                followRepository.createFollow(user.id.value, creator.id.value)
                followRepository.createFollow(other.id.value, user.id.value)

                pointRepository.createPointAccount(user.id.value)
                pointRepository.updateBalance(user.id.value, BigDecimal("5000"))
                pointHistoryRepository.create(
                    userId = user.id.value,
                    type = PointType.EARN,
                    amount = BigDecimal("5000"),
                    balanceBefore = BigDecimal.ZERO,
                    balanceAfter = BigDecimal("5000"),
                    description = "테스트 적립"
                )
            }
            TestFixtures.createTestLike(user.id.value, likedPost.id.value)

            authService.deleteAccount(user.id.value)

            query {
                assertTrue(addressRepository.findAddresses(user.id.value).isEmpty(), "주소록 삭제")
                assertEquals(0L, searchHistoryRepository.countHistories(user.id.value), "검색기록 삭제")
                assertTrue(cartRepository.findUserCart(user.id.value).isEmpty(), "카트 삭제")
                assertEquals(0L, userCouponRepository.countByUserId(user.id.value), "보유 쿠폰 삭제")
                assertEquals(0, notificationRepository.countTotalNotifications(user.id.value), "수신 알림 삭제")
                assertFalse(blockedUserRepository.isBlocked(user.id.value, other.id.value), "내 차단 해제")
                assertFalse(blockedUserRepository.isBlocked(other.id.value, user.id.value), "상대의 나 차단 해제")
                assertFalse(wishlistRepository.isInWishlist(user.id.value, product.id.value), "위시 해제")
                assertEquals(0, productRepository.findProductById(product.id.value)?.likeCount, "상품 찜 수 원복")
                assertFalse(followRepository.isFollowing(user.id.value, creator.id.value), "팔로잉 해제")
                assertFalse(followRepository.isFollowing(other.id.value, user.id.value), "팔로워 해제")
                assertEquals(0, followRepository.countFollowers(creator.id.value), "크리에이터 팔로워 수 정합")

                val point = pointRepository.findByUserId(user.id.value)
                assertNotNull(point, "포인트 계정 row는 유지(환불 경로 보호)")
                assertTrue(BigDecimal.ZERO.compareTo(point.balance) == 0, "포인트 잔액 0")
                assertEquals(0L, pointHistoryRepository.countByUserId(user.id.value), "포인트 내역 삭제")

                // 좋아요는 유지 정책 — 글 좋아요 잔존
                assertTrue(likeRepository.isPostLiked(user.id.value, likedPost.id.value), "좋아요 잔존")
            }
        }
    }

    @Test
    fun `계정 탈퇴 - ex-크리에이터 유산 정리(상품 soft-delete, 상세 이미지·숨긴 글 미디어만 수거)`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val listImageUrl = "https://supabase.example/storage/v1/object/public/products/list-keep.png"
            val detailImageUrl = "https://supabase.example/storage/v1/object/public/products/detail-collect.png"
            val mediaUrl = "https://supabase.example/storage/v1/object/public/posts/media-collect.png"
            val mediaThumbUrl = "https://supabase.example/storage/v1/object/public/posts/media-thumb.png"

            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                imageUrls = "[\"$listImageUrl\"]"
            )
            val communityPost = TestFixtures.createTestPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                contextType = PostContextType.COMMUNITY
            )
            query {
                productRepository.updateDetailContent(
                    product.id.value,
                    "[{\"type\":\"image\",\"url\":\"$detailImageUrl\"}]"
                )
                PostMediaDao.new {
                    this.postId = communityPost.id.value
                    this.type = MediaType.IMAGE
                    this.url = mediaUrl
                    this.thumbnailUrl = mediaThumbUrl
                    this.sortOrder = 0
                }
                // 크리에이터 해제 완료 상태 재현: 글 숨김 + role USER 강등
                postRepository.hideCreatorPosts(creator.id.value)
                userRepository.demoteCreator(creator.id.value)
            }

            authService.deleteAccount(creator.id.value)

            query {
                // 상품 soft-delete — 일반 조회에선 사라지고, 주문 표시용 WithDeleted로는 여전히 읽힌다
                assertNull(productRepository.findProductById(product.id.value))
                val withDeleted = productRepository.findProductsByIdsWithDeleted(listOf(product.id.value)).single()
                assertFalse(withDeleted.isActive)
            }

            // 파일 수거: 상세 콘텐츠 이미지 + 숨긴 글 미디어(원본/썸네일)만 — 목록 이미지는 주문 표시용 보존
            coVerify { fileUploadService.deleteFileIfSupabase(detailImageUrl) }
            coVerify { fileUploadService.deleteFileIfSupabase(mediaUrl) }
            coVerify { fileUploadService.deleteFileIfSupabase(mediaThumbUrl) }
            coVerify(exactly = 0) { fileUploadService.deleteFileIfSupabase(listImageUrl) }
        }
    }

    @Test
    fun `계정 탈퇴 - PENDING 크리에이터 신청은 삭제, 처리된 이력은 보존`() {
        runBlocking {
            // PENDING 신청은 삭제(관리자 목록 유령/사후 승격 방지)
            val user = TestFixtures.createTestUser()
            query {
                creatorApplicationRepository.createApplication(user.id.value, "크리에이터로 활동하고 싶습니다.", null)
            }
            authService.deleteAccount(user.id.value)
            query {
                assertNull(creatorApplicationRepository.findByUserId(user.id.value), "PENDING 신청 삭제")
            }

            // REVOKED 등 처리된 이력은 감사 기록으로 보존
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            query {
                val application = creatorApplicationRepository.createApplication(user2.id.value, "크리에이터로 활동하고 싶습니다.", null)
                application.status = CreatorApplicationStatus.REVOKED
            }
            authService.deleteAccount(user2.id.value)
            query {
                assertEquals(
                    CreatorApplicationStatus.REVOKED,
                    creatorApplicationRepository.findByUserId(user2.id.value)?.status
                )
            }
        }
    }
}
