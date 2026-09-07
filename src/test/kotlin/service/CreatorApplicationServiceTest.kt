package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponStatus
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.CreatorApplicationStatus
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostStatus
import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.CreatorApplicationNotFoundException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.ValidationException
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.features.commerce.data.OrderRepositoryImpl
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.coupon.data.CouponRepositoryImpl
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.user.data.CreatorApplicationRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.features.user.domain.CreatorApplicationService
import com.ninezero.features.user.presentation.models.request.CreatorApplicationRequest
import com.ninezero.features.user.presentation.models.request.RejectApplicationRequest
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.plusDays
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CreatorApplicationService 테스트
 *
 * 테스트 케이스: 15개
 * - 크리에이터 신청: 5개
 * - 신청 조회: 2개
 * - 신청 승인: 3개
 * - 신청 거절: 3개
 * - 신청 취소: 2개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreatorApplicationServiceTest {

    private lateinit var applicationService: CreatorApplicationService
    private lateinit var applicationRepository: CreatorApplicationRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var notificationService: NotificationService
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var orderRepository: OrderRepositoryImpl
    private lateinit var planRepository: SubscriptionPlanRepositoryImpl
    private lateinit var productRepository: ProductRepositoryImpl
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var couponRepository: CouponRepositoryImpl
    private lateinit var cacheService: CacheService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            applicationRepository = CreatorApplicationRepositoryImpl()
            userRepository = UserRepositoryImpl()
            subscriptionRepository = SubscriptionRepositoryImpl()
            orderRepository = OrderRepositoryImpl()
            planRepository = SubscriptionPlanRepositoryImpl()
            productRepository = ProductRepositoryImpl()
            postRepository = PostRepositoryImpl()
            couponRepository = CouponRepositoryImpl()

            // Mock 객체 생성
            notificationService = mockk(relaxed = true)
            cacheService = mockk(relaxed = true)

            // Mock 동작 정의
            coEvery {
                notificationService.sendCreatorApprovedNotification(any(), any())
            } returns mockk()

            coEvery {
                notificationService.sendCreatorRejectedNotification(any(), any(), any())
            } returns mockk()

            coEvery {
                notificationService.sendCreatorDemotedNotification(any(), any())
            } returns mockk()

            applicationService = CreatorApplicationService(
                applicationRepository = applicationRepository,
                userRepository = userRepository,
                notificationService = notificationService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
                subscriptionRepository = subscriptionRepository,
                orderRepository = orderRepository,
                subscriptionPlanRepository = planRepository,
                productRepository = productRepository,
                postRepository = postRepository,
                couponRepository = couponRepository,
                cacheService = cacheService
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()
            // 알림 호출 기록 초기화 (스텁 유지) — 테스트 간 coVerify 오염 방지
            clearMocks(notificationService, answers = false)
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    // ===== 크리에이터 신청 테스트 (5개) =====

    @Test
    fun `크리에이터 신청 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            val request = CreatorApplicationRequest(
                reason = "저는 VTuber 굿즈를 판매하고 싶습니다.",
                portfolioUrl = "https://portfolio.example.com"
            )

            val response = applicationService.applyForCreator(user.id.value, request)

            assertNotNull(response)
            assertEquals(user.id.value, response.userId)
            assertEquals(CreatorApplicationStatus.PENDING, response.status)
            assertEquals("저는 VTuber 굿즈를 판매하고 싶습니다.", response.reason)
        }
    }

    @Test
    fun `크리에이터 신청 실패 - 이미 크리에이터`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = CreatorApplicationRequest(
                reason = "신청합니다.",
                portfolioUrl = null
            )

            assertFailsWith<ValidationException> {
                applicationService.applyForCreator(creator.id.value, request)
            }
        }
    }

    @Test
    fun `크리에이터 신청 실패 - 관리자는 신청 불가`() {
        runBlocking {
            val admin = TestFixtures.createTestAdmin()

            val request = CreatorApplicationRequest(
                reason = "신청합니다.",
                portfolioUrl = null
            )

            assertFailsWith<ValidationException> {
                applicationService.applyForCreator(admin.id.value, request)
            }
        }
    }

    @Test
    fun `크리에이터 신청 실패 - 이미 대기 중인 신청 존재`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            val request = CreatorApplicationRequest(
                reason = "크리에이터로 활동하고 싶습니다.",
                portfolioUrl = null
            )

            // 첫 번째 신청 성공
            applicationService.applyForCreator(user.id.value, request)

            // 두 번째 신청 시도 (중복)
            assertFailsWith<ConflictException> {
                applicationService.applyForCreator(user.id.value, request)
            }
        }
    }

    @Test
    fun `크리에이터 신청 성공 - 거절 후 재신청 (쿨다운 기간 경과)`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)
            val admin = TestFixtures.createTestAdmin()

            // 첫 번째 신청
            val request1 = CreatorApplicationRequest(
                reason = "크리에이터로 활동하고 싶습니다.",
                portfolioUrl = null
            )
            val app1 = applicationService.applyForCreator(user.id.value, request1)

            // 거절
            applicationService.rejectApplication(
                app1.id,
                admin.id.value,
                RejectApplicationRequest("사유 불충분 사유 불충분 사유 불충분 사유 불충분")
            )

            // 재신청 (실제로는 쿨다운 기간이 있지만, 테스트에서는 즉시 재신청 시도)
            // 이 테스트는 쿨다운 로직을 검증하기 위한 것이므로 실패할 것으로 예상
            val request2 = CreatorApplicationRequest(
                reason = "재신청합니다. 더 자세한 사유를 추가했습니다.",
                portfolioUrl = "https://portfolio.example.com"
            )

            // 쿨다운 기간 내 재신청 시도 - 실패 예상
            assertFailsWith<ValidationException> {
                applicationService.applyForCreator(user.id.value, request2)
            }
        }
    }

    // ===== 신청 조회 테스트 (2개) =====

    @Test
    fun `내 신청 조회 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            val request = CreatorApplicationRequest(
                reason = "크리에이터로 활동하고 싶습니다.",
                portfolioUrl = null
            )

            applicationService.applyForCreator(user.id.value, request)

            val response = applicationService.getMyApplication(user.id.value)

            assertNotNull(response)
            assertEquals(user.id.value, response.userId)
            assertEquals(CreatorApplicationStatus.PENDING, response.status)
        }
    }

    @Test
    fun `내 신청 조회 실패 - 신청 내역 없음`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            assertFailsWith<NotFoundException> {
                applicationService.getMyApplication(user.id.value)
            }
        }
    }

    // ===== 신청 승인 테스트 (3개) =====

    @Test
    fun `신청 승인 성공 - Role이 CREATOR로 변경`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)
            val admin = TestFixtures.createTestAdmin()

            val request = CreatorApplicationRequest(
                reason = "크리에이터로 활동하고 싶습니다.",
                portfolioUrl = null
            )

            val application = applicationService.applyForCreator(user.id.value, request)

            // 승인
            val message = applicationService.approveApplication(application.id, admin.id.value)

            assertNotNull(message)
            assertTrue(message.contains("승인") || message.contains("APPROVED"))

            // Role 확인
            val updatedUser = query {
                userRepository.findUserById(user.id.value)
            }
            assertEquals(UserRole.CREATOR, updatedUser?.role)
        }
    }

    @Test
    fun `신청 승인 실패 - 이미 처리된 신청`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)
            val admin = TestFixtures.createTestAdmin()

            val request = CreatorApplicationRequest(
                reason = "크리에이터로 활동하고 싶습니다.",
                portfolioUrl = null
            )

            val application = applicationService.applyForCreator(user.id.value, request)

            // 첫 번째 승인
            applicationService.approveApplication(application.id, admin.id.value)

            // 두 번째 승인 시도
            assertFailsWith<ValidationException> {
                applicationService.approveApplication(application.id, admin.id.value)
            }
        }
    }

    @Test
    fun `신청 승인 실패 - 존재하지 않는 신청`() {
        runBlocking {
            val admin = TestFixtures.createTestAdmin()

            assertFailsWith<CreatorApplicationNotFoundException> {
                applicationService.approveApplication(99999, admin.id.value)
            }
        }
    }

    // ===== 신청 거절 테스트 (3개) =====

    @Test
    fun `신청 거절 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)
            val admin = TestFixtures.createTestAdmin()

            val request = CreatorApplicationRequest(
                reason = "크리에이터로 활동하고 싶습니다.",
                portfolioUrl = null
            )

            val application = applicationService.applyForCreator(user.id.value, request)

            // 거절
            val rejectRequest = RejectApplicationRequest("사유가 불충분합니다. 사유가 불충분합니다.")
            val message = applicationService.rejectApplication(
                application.id,
                admin.id.value,
                rejectRequest
            )

            assertNotNull(message)
            assertTrue(message.contains("거절") || message.contains("REJECTED"))

            // 상태 확인
            val updatedApp = applicationService.getMyApplication(user.id.value)
            assertEquals(CreatorApplicationStatus.REJECTED, updatedApp.status)
            assertEquals("사유가 불충분합니다. 사유가 불충분합니다.", updatedApp.rejectionReason)
        }
    }

    @Test
    fun `신청 거절 후 Role 확인 - USER 유지`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)
            val admin = TestFixtures.createTestAdmin()

            val request = CreatorApplicationRequest(
                reason = "크리에이터로 활동하고 싶습니다.",
                portfolioUrl = null
            )

            val application = applicationService.applyForCreator(user.id.value, request)

            // 거절
            applicationService.rejectApplication(
                application.id,
                admin.id.value,
                RejectApplicationRequest("거절 사유 거절 사유 거절 사유 거절 사유")
            )

            // Role 확인 (USER 유지)
            val updatedUser = query {
                userRepository.findUserById(user.id.value)
            }
            assertEquals(UserRole.USER, updatedUser?.role)
        }
    }

    @Test
    fun `신청 거절 실패 - 이미 처리된 신청`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)
            val admin = TestFixtures.createTestAdmin()

            val request = CreatorApplicationRequest(
                reason = "크리에이터로 활동하고 싶습니다.",
                portfolioUrl = null
            )

            val application = applicationService.applyForCreator(user.id.value, request)

            // 첫 번째 거절
            applicationService.rejectApplication(
                application.id,
                admin.id.value,
                RejectApplicationRequest("거절 사유 거절 사유 거절 사유 거절 사유")
            )

            // 두 번째 거절 시도
            assertFailsWith<ValidationException> {
                applicationService.rejectApplication(
                    application.id,
                    admin.id.value,
                    RejectApplicationRequest("거절 사유 거절 사유 거절 사유 거절 사유")
                )
            }
        }
    }

    // ===== 신청 취소 테스트 (2개) =====

    @Test
    fun `신청 취소 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            val request = CreatorApplicationRequest(
                reason = "크리에이터로 활동하고 싶습니다.",
                portfolioUrl = null
            )

            applicationService.applyForCreator(user.id.value, request)

            // 취소
            applicationService.cancelApplication(user.id.value)

            // 취소 후 조회 시도 (실패)
            assertFailsWith<NotFoundException> {
                applicationService.getMyApplication(user.id.value)
            }
        }
    }

    @Test
    fun `신청 취소 실패 - 이미 처리된 신청은 취소 불가`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)
            val admin = TestFixtures.createTestAdmin()

            val request = CreatorApplicationRequest(
                reason = "크리에이터로 활동하고 싶습니다.",
                portfolioUrl = null
            )

            val application = applicationService.applyForCreator(user.id.value, request)

            // 승인
            applicationService.approveApplication(application.id, admin.id.value)

            // 승인된 신청 취소 시도
            assertFailsWith<ValidationException> {
                applicationService.cancelApplication(user.id.value)
            }
        }
    }

    // ===== 크리에이터 셀프 해제 테스트 =====

    @Test
    fun `셀프 해제 성공 - 자산 정리 및 REVOKED 전환`() {
        runBlocking {
            val admin = TestFixtures.createTestAdmin()
            val user = TestFixtures.createTestUser(role = UserRole.USER)
            val follower = TestFixtures.createTestUser(email = "follower@example.com", username = "follower")

            // 신청 → 승인으로 크리에이터 전환 (application 이력 확보)
            val application = applicationService.applyForCreator(
                user.id.value,
                CreatorApplicationRequest("크리에이터로 활동하고 싶습니다.", null)
            )
            applicationService.approveApplication(application.id, admin.id.value)

            // 자산 생성: 플랜 / 딜 상품 / 쿠폰 / 커뮤니티 글(본인+팔로워) / 티어 글 / FREE 글
            val plan = query {
                planRepository.createPlan(
                    creatorId = user.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택\"]"
                )
            }
            val product = TestFixtures.createTestProduct(creatorId = user.id.value)
            query {
                productRepository.setProductDeal(
                    productId = product.id.value,
                    dealPrice = BigDecimal("8000"),
                    startAt = nowUtc(),
                    endAt = nowUtc().plusDays(3)
                )
            }
            val coupon = query {
                couponRepository.create(
                    creatorId = user.id.value,
                    code = "TESTCODE1234",
                    name = "테스트 쿠폰",
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
            }
            val ownCommunityPost = TestFixtures.createTestPost(
                userId = user.id.value, creatorId = user.id.value, contextType = PostContextType.COMMUNITY
            )
            val followerCommunityPost = TestFixtures.createTestPost(
                userId = follower.id.value, creatorId = user.id.value, contextType = PostContextType.COMMUNITY
            )
            val tierPost = TestFixtures.createTestPost(
                userId = user.id.value, creatorId = user.id.value,
                contextType = PostContextType.CREATOR_FEED, requiredTier = SubscriptionPlanTier.TIER1
            )
            val freePost = TestFixtures.createTestPost(
                userId = user.id.value, creatorId = user.id.value, contextType = PostContextType.CREATOR_FEED
            )

            // 셀프 해제
            val message = applicationService.revokeSelf(user.id.value)
            assertEquals("크리에이터가 해제되었습니다.", message)

            query {
                // role 강등
                assertEquals(UserRole.USER, userRepository.findUserById(user.id.value)?.role)

                // 신청 이력 REVOKED + 본인 요청 사유
                val updatedApp = applicationRepository.findByUserId(user.id.value)
                assertEquals(CreatorApplicationStatus.REVOKED, updatedApp?.status)
                assertEquals("본인 요청으로 해제되었습니다", updatedApp?.rejectionReason)
                assertEquals(user.id.value, updatedApp?.reviewedBy)

                // 플랜 비활성
                assertEquals(false, planRepository.findPlanById(plan.id.value)?.isActive)

                // 상품 판매종료 + 타임딜 해제
                val updatedProduct = productRepository.findProductById(product.id.value)
                assertEquals(ProductStatus.DISCONTINUED, updatedProduct?.status)
                assertEquals(null, updatedProduct?.dealPrice)

                // 커뮤니티 글(팔로워 작성 포함)·티어 글 숨김 (status=HIDDEN 마커)
                val hiddenPosts = postRepository.findPostsByIdsWithDeleted(
                    listOf(ownCommunityPost.id.value, followerCommunityPost.id.value, tierPost.id.value)
                )
                assertEquals(3, hiddenPosts.size)
                assertTrue(hiddenPosts.all { !it.isActive && it.status == PostStatus.HIDDEN })

                // FREE 피드 글은 일반 글로 잔존
                assertNotNull(postRepository.findPostById(freePost.id.value))

                // 쿠폰 비활성
                assertEquals(CouponStatus.DISABLED, couponRepository.findById(coupon.id.value)?.status)
            }

            // 본인 액션 — 강등 알림 미발송
            coVerify(exactly = 0) { notificationService.sendCreatorDemotedNotification(any(), any()) }
        }
    }

    @Test
    fun `셀프 해제 실패 - 활성 구독자 존재`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val subscriber = TestFixtures.createTestUser()

            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택\"]"
                )
            }
            query {
                subscriptionRepository.createSubscription(
                    userId = subscriber.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().plusDays(30),
                    autoRenew = true
                )
            }

            val exception = assertFailsWith<ValidationException> {
                applicationService.revokeSelf(creator.id.value)
            }
            assertTrue(exception.message!!.contains("활성 구독자 1명"))

            // 아무것도 변경되지 않음 — 역할/플랜 유지
            query {
                assertEquals(UserRole.CREATOR, userRepository.findUserById(creator.id.value)?.role)
                assertEquals(true, planRepository.findPlanById(plan.id.value)?.isActive)
            }
        }
    }

    @Test
    fun `셀프 해제 실패 - 진행 중 주문 존재`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)

            TestFixtures.createTestOrder(userId = buyer.id.value, productId = product.id.value)

            val exception = assertFailsWith<ValidationException> {
                applicationService.revokeSelf(creator.id.value)
            }
            assertTrue(exception.message!!.contains("진행 중 주문 1건"))

            query {
                assertEquals(UserRole.CREATOR, userRepository.findUserById(creator.id.value)?.role)
            }
        }
    }

    @Test
    fun `셀프 해제 실패 - 크리에이터 아님`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            assertFailsWith<ValidationException> {
                applicationService.revokeSelf(user.id.value)
            }
        }
    }

    @Test
    fun `관리자 강등 - 셀프 해제와 동일한 사전조건 적용`() {
        runBlocking {
            val admin = TestFixtures.createTestAdmin()
            val creator = TestFixtures.createTestCreator()
            val subscriber = TestFixtures.createTestUser()

            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택\"]"
                )
            }
            query {
                subscriptionRepository.createSubscription(
                    userId = subscriber.id.value,
                    creatorId = creator.id.value,
                    planId = plan.id.value,
                    startedAt = nowUtc(),
                    expiresAt = nowUtc().plusDays(30),
                    autoRenew = true
                )
            }

            val exception = assertFailsWith<ValidationException> {
                applicationService.demoteCreator(
                    creator.id.value,
                    "운영 정책 위반으로 강등합니다. 운영 정책 위반으로 강등합니다.",
                    admin.id.value
                )
            }
            assertTrue(exception.message!!.contains("활성 구독자 1명"))

            query {
                assertEquals(UserRole.CREATOR, userRepository.findUserById(creator.id.value)?.role)
            }
        }
    }

    @Test
    fun `관리자 강등 성공 - teardown 적용 및 알림 발송`() {
        runBlocking {
            val admin = TestFixtures.createTestAdmin()
            val creator = TestFixtures.createTestCreator()

            val plan = query {
                planRepository.createPlan(
                    creatorId = creator.id.value,
                    name = "기본 플랜",
                    tier = SubscriptionPlanTier.TIER1,
                    description = "기본 구독 플랜",
                    price = BigDecimal("5000"),
                    benefits = "[\"혜택\"]"
                )
            }
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)

            val message = applicationService.demoteCreator(
                creator.id.value,
                "운영 정책 위반으로 강등합니다. 운영 정책 위반으로 강등합니다.",
                admin.id.value
            )
            assertNotNull(message)

            query {
                assertEquals(UserRole.USER, userRepository.findUserById(creator.id.value)?.role)
                assertEquals(false, planRepository.findPlanById(plan.id.value)?.isActive)
                assertEquals(ProductStatus.DISCONTINUED, productRepository.findProductById(product.id.value)?.status)
            }

            // 관리자 강등은 알림 발송
            coVerify(exactly = 1) { notificationService.sendCreatorDemotedNotification(creator.id.value, any()) }
        }
    }

    @Test
    fun `재승인 시 숨긴 글만 복구 - 자진삭제 글과 상품은 미복구`() {
        runBlocking {
            val admin = TestFixtures.createTestAdmin()
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            // 신청 → 승인으로 크리에이터 전환
            val application = applicationService.applyForCreator(
                user.id.value,
                CreatorApplicationRequest("크리에이터로 활동하고 싶습니다.", null)
            )
            applicationService.approveApplication(application.id, admin.id.value)

            // 자산: 커뮤니티 글 / 티어 글 / 자진삭제 글 / 상품
            val communityPost = TestFixtures.createTestPost(
                userId = user.id.value, creatorId = user.id.value, contextType = PostContextType.COMMUNITY
            )
            val tierPost = TestFixtures.createTestPost(
                userId = user.id.value, creatorId = user.id.value,
                contextType = PostContextType.CREATOR_FEED, requiredTier = SubscriptionPlanTier.TIER1
            )
            val selfDeletedPost = TestFixtures.createTestPost(
                userId = user.id.value, creatorId = user.id.value, contextType = PostContextType.COMMUNITY
            )
            query { postRepository.deletePost(selfDeletedPost.id.value) }
            val product = TestFixtures.createTestProduct(creatorId = user.id.value)

            // 셀프 해제
            applicationService.revokeSelf(user.id.value)

            // 재신청은 7일 쿨다운에 걸리므로 repository로 직접 신청 생성 후 승인
            val reApplication = query {
                applicationRepository.createApplication(
                    userId = user.id.value,
                    reason = "다시 크리에이터로 활동하고 싶습니다.",
                    portfolioUrl = null
                )
            }
            applicationService.approveApplication(reApplication.id.value, admin.id.value)

            query {
                // 크리에이터 복귀
                assertEquals(UserRole.CREATOR, userRepository.findUserById(user.id.value)?.role)

                // 숨긴 글(HIDDEN 마커)만 복구
                val restoredCommunity = postRepository.findPostById(communityPost.id.value)
                val restoredTier = postRepository.findPostById(tierPost.id.value)
                assertNotNull(restoredCommunity)
                assertNotNull(restoredTier)
                assertEquals(PostStatus.ACTIVE, restoredCommunity.status)
                assertEquals(PostStatus.ACTIVE, restoredTier.status)

                // 자진삭제 글은 안 살아남
                val stillDeleted = postRepository.findPostsByIdsWithDeleted(listOf(selfDeletedPost.id.value)).first()
                assertEquals(false, stillDeleted.isActive)

                // 상품은 판매종료 유지 (본인이 개별 재판매 토글)
                assertEquals(ProductStatus.DISCONTINUED, productRepository.findProductById(product.id.value)?.status)
            }
        }
    }
}
