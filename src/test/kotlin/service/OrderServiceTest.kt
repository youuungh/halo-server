package com.ninezero.service

import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.PaymentProvider
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.config.ShippingStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.delivery.DeliveryApiClient
import com.ninezero.core.payment.TossCancelResult
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.core.payment.TossPaymentResponse
import com.ninezero.core.common.util.query
import com.ninezero.core.common.exception.EmptyCartException
import com.ninezero.core.common.exception.InsufficientStockException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.OrderNotFoundException
import com.ninezero.core.common.exception.PermissionDeniedException
import com.ninezero.core.common.exception.ProductInactiveException
import com.ninezero.core.common.exception.ProductSoldOutException
import com.ninezero.core.common.exception.SubscriptionRequiredException
import com.ninezero.features.commerce.data.CartRepositoryImpl
import com.ninezero.features.commerce.data.OrderRepositoryImpl
import com.ninezero.features.commerce.data.PaymentRepositoryImpl
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.data.ReviewRepositoryImpl
import com.ninezero.features.commerce.domain.CartService
import com.ninezero.features.commerce.domain.OrderService
import com.ninezero.features.commerce.presentation.models.request.OrderRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateOrderStatusRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateShippingRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateShippingStatusRequest
import com.ninezero.features.coupon.data.CouponRepositoryImpl
import com.ninezero.features.coupon.data.UserCouponRepositoryImpl
import com.ninezero.features.coupon.domain.CouponRedemptionService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.point.data.PointHistoryRepositoryImpl
import com.ninezero.features.point.data.PointRepositoryImpl
import com.ninezero.features.point.domain.PointEarnService
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * OrderService 테스트
 *
 * 테스트 케이스: 16개
 * - 주문 생성: 5개
 * - 주문 조회: 3개
 * - 주문 취소: 4개
 * - 배송 관리: 4개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderServiceTest {

    private lateinit var orderService: OrderService
    private lateinit var notificationService: NotificationService
    private lateinit var tossPaymentClient: TossPaymentClient
    private lateinit var productRepository: ProductRepositoryImpl
    private lateinit var cartRepository: CartRepositoryImpl
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var subscriptionPlanRepository: SubscriptionPlanRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            // Mock 객체 생성 (NotificationService의 모든 메서드를 relaxed로 처리)
            notificationService = mockk(relaxed = true)
            // 실 TossPaymentClient는 DotenvConfig로 .env의 TOSS_SECRET_KEY를 읽어 활성화되므로,
            // 취소 경로를 타는 테스트가 토스 서버로 실제 요청을 보내게 된다. 반드시 mock을 쓴다.
            tossPaymentClient = mockk(relaxed = true)

            // 하위 의존성 초기화
            val pointRepository = PointRepositoryImpl()
            val pointHistoryRepository = PointHistoryRepositoryImpl()
            val orderRepository = OrderRepositoryImpl()
            val reviewRepository = ReviewRepositoryImpl()

            val pointService = PointService(
                pointRepository = pointRepository,
                pointHistoryRepository = pointHistoryRepository,
                notificationService = notificationService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            )

            val pointEarnService = PointEarnService(
                pointService = pointService
            )

            subscriptionRepository = SubscriptionRepositoryImpl()
            subscriptionPlanRepository = SubscriptionPlanRepositoryImpl()
            productRepository = ProductRepositoryImpl()
            cartRepository = CartRepositoryImpl()
            val cartService = CartService(
                cartRepository = cartRepository,
                productRepository = productRepository,
                subscriptionRepository = subscriptionRepository,
                planRepository = subscriptionPlanRepository
            )

            val couponRedemptionService = CouponRedemptionService(
                couponRepository = CouponRepositoryImpl(),
                userCouponRepository = UserCouponRepositoryImpl(),
                subscriptionRepository = subscriptionRepository,
                subscriptionPlanRepository = subscriptionPlanRepository
            )
            val deliveryApiClient = mockk<DeliveryApiClient>(relaxed = true)

            // OrderService 초기화
            orderService = OrderService(
                orderRepository = orderRepository,
                productRepository = productRepository,
                reviewRepository = reviewRepository,
                cartRepository = cartRepository,
                userRepository = UserRepositoryImpl(),
                paymentRepository = PaymentRepositoryImpl(),
                pointRepository = pointRepository,
                notificationService = notificationService,
                pointEarnService = pointEarnService,
                pointService = pointService,
                couponRedemptionService = couponRedemptionService,
                couponRepository = CouponRepositoryImpl(),
                subscriptionRepository = subscriptionRepository,
                subscriptionPlanRepository = subscriptionPlanRepository,
                cartService = cartService,
                deliveryApiClient = deliveryApiClient,
                tossPaymentClient = tossPaymentClient,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
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

    // ===== 주문 생성 테스트 (5개) =====

    @Test
    fun `장바구니에서 주문 생성 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)
            TestFixtures.addToCart(user.id.value, product.id.value, 2)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구 테헤란로 123",
                paymentProvider = PaymentProvider.MOCK,
                memo = "배송 전 연락주세요",
                pointsToUse = null,
                couponCodes = null
            )

            // When
            val response = orderService.createOrderFromCart(user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(OrderStatus.PENDING, response.status)
            assertEquals("홍길동", response.shippingName)
        }
    }

    @Test
    fun `빈 장바구니로 주문 생성 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            // When & Then
            assertFailsWith<EmptyCartException> {
                orderService.createOrderFromCart(user.id.value, request)
            }
        }
    }

    @Test
    fun `장바구니에서 주문 생성 실패 - 판매 중단 상품`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                status = ProductStatus.DISCONTINUED
            )
            TestFixtures.addToCart(user.id.value, product.id.value, 1)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구 테헤란로 123",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            assertFailsWith<ProductInactiveException> {
                orderService.createOrderFromCart(user.id.value, request)
            }
        }
    }

    @Test
    fun `장바구니에서 주문 생성 실패 - 품절 상품`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 0,
                status = ProductStatus.SOLD_OUT
            )
            TestFixtures.addToCart(user.id.value, product.id.value, 1)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구 테헤란로 123",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            assertFailsWith<ProductSoldOutException> {
                orderService.createOrderFromCart(user.id.value, request)
            }
        }
    }

    @Test
    fun `장바구니에서 주문 생성 실패 - 재고 부족`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 1
            )
            TestFixtures.addToCart(user.id.value, product.id.value, 2)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구 테헤란로 123",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            assertFailsWith<InsufficientStockException> {
                orderService.createOrderFromCart(user.id.value, request)
            }
        }
    }

    @Test
    fun `장바구니에서 주문 생성 실패 - 멤버십 접근 불가`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                requiredTier = SubscriptionPlanTier.TIER1
            )
            TestFixtures.addToCart(user.id.value, product.id.value, 1)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구 테헤란로 123",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            assertFailsWith<SubscriptionRequiredException> {
                orderService.createOrderFromCart(user.id.value, request)
            }
        }
    }

    @Test
    fun `장바구니에서 주문 생성 실패 - 자신의 상품 우회 데이터`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            TestFixtures.addToCart(creator.id.value, product.id.value, 1)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구 테헤란로 123",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            assertFailsWith<InvalidInputException> {
                orderService.createOrderFromCart(creator.id.value, request)
            }
        }
    }

    @Test
    fun `잘못된 배송 정보로 주문 생성 실패 - 빈 이름`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            TestFixtures.addToCart(user.id.value, product.id.value, 1)

            val request = OrderRequest(
                shippingName = "",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            // When & Then
            assertFailsWith<Exception> {  // ValidationException 또는 BadRequestException
                orderService.createOrderFromCart(user.id.value, request)
            }
        }
    }

    @Test
    fun `잘못된 배송 정보로 주문 생성 실패 - 잘못된 전화번호`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            TestFixtures.addToCart(user.id.value, product.id.value, 1)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "123",
                shippingAddress = "서울시 강남구",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            // When & Then
            assertFailsWith<Exception> {
                orderService.createOrderFromCart(user.id.value, request)
            }
        }
    }

    @Test
    fun `잘못된 배송 정보로 주문 생성 실패 - 빈 주소`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            TestFixtures.addToCart(user.id.value, product.id.value, 1)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            // When & Then
            assertFailsWith<Exception> {
                orderService.createOrderFromCart(user.id.value, request)
            }
        }
    }

    // ===== 주문 조회 테스트 (3개) =====

    @Test
    fun `주문 상세 조회 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)

            // When
            val response = orderService.getOrderById(order.id.value, user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(order.id.value, response.id)
        }
    }

    @Test
    fun `주문 상세 조회 - 삭제된 상품도 실명 유지 및 isProductActive=false`() {
        runBlocking {
            // Given — 주문 후 상품이 soft-delete된 상황(판매자 탈퇴 등)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value, name = "한정판 굿즈")
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)
            query { ProductRepositoryImpl().deleteProduct(product.id.value) }

            // When
            val response = orderService.getOrderById(order.id.value, user.id.value)

            // Then — 이름은 WithDeleted 조회로 보존(스냅샷 등가), 판매 가능 여부만 꺼진다
            val item = response.items.single()
            assertEquals("한정판 굿즈", item.productName)
            assertFalse(item.isProductActive)
        }
    }

    @Test
    fun `사용자 주문 목록 조회 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)

            repeat(3) {
                TestFixtures.createTestOrder(
                    user.id.value,
                    product.id.value,
                    paymentStatus = PaymentStatus.COMPLETED
                )
            }

            // When
            val response = orderService.getMyOrders(
                userId = user.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(3, response.items.size)
        }
    }

    @Test
    fun `다른 사용자의 주문 조회 실패`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user1.id.value, product.id.value)

            // When & Then
            assertFailsWith<PermissionDeniedException> {
                orderService.getOrderById(order.id.value, user2.id.value)
            }
        }
    }

    // ===== 주문 취소 테스트 (3개) =====

    @Test
    fun `주문 취소 성공 - PENDING 상태`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)

            // When
            val response = orderService.cancelOrder(order.id.value, user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(OrderStatus.CANCELLED, response.status)
        }
    }

    @Test
    fun `다른 사용자가 주문 취소 실패`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user1.id.value, product.id.value)

            // When & Then
            assertFailsWith<PermissionDeniedException> {
                orderService.cancelOrder(order.id.value, user2.id.value)
            }
        }
    }

    @Test
    fun `존재하지 않는 주문 취소 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When & Then
            assertFailsWith<OrderNotFoundException> {
                orderService.cancelOrder(99999, user.id.value)
            }
        }
    }

    /**
     * 회귀 방지 — 저장카드(TOSS_BILLING) 결제는 payByBilling이 provider enum을 갱신하지 않아
     * TOSS_BILLING으로 남는다. 취소 게이트가 TOSS_PAYMENTS만 보면 실제 청구건이 조용히
     * 통과해 토스 취소 없이 DB만 REFUNDED가 된다.
     */
    @Test
    fun `저장카드(TOSS_BILLING) 결제완료 주문 취소 시 토스 취소를 호출한다`() {
        runBlocking {
            // Given
            // @TestInstance(PER_CLASS)라 mock이 클래스 전체에 공유된다 — 호출 기록을 먼저 비운다.
            clearMocks(tossPaymentClient)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value,
                paymentStatus = PaymentStatus.COMPLETED,
                paymentProvider = PaymentProvider.TOSS_BILLING,
                paymentKey = "test_billing_payment_key"
            )
            coEvery {
                tossPaymentClient.cancel(any(), any(), any(), any())
            } returns TossCancelResult.Success(
                TossPaymentResponse(
                    paymentKey = "test_billing_payment_key",
                    orderId = order.orderNumber,
                    status = "CANCELED",
                    totalAmount = 10000L
                )
            )

            // When
            val response = orderService.cancelOrder(order.id.value, user.id.value)

            // Then
            assertEquals(OrderStatus.CANCELLED, response.status)
            coVerify(exactly = 1) {
                tossPaymentClient.cancel("test_billing_payment_key", any(), any(), any())
            }
        }
    }

    @Test
    fun `MOCK 결제완료 주문 취소는 토스를 호출하지 않는다`() {
        runBlocking {
            // Given
            clearMocks(tossPaymentClient)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value,
                paymentStatus = PaymentStatus.COMPLETED
            )

            // When
            val response = orderService.cancelOrder(order.id.value, user.id.value)

            // Then
            assertEquals(OrderStatus.CANCELLED, response.status)
            coVerify(exactly = 0) { tossPaymentClient.cancel(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `판매자 주문 취소 성공 - 상태 변경 API`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser(email = "buyer@test.com", username = "buyer")
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)

            // When
            val response = orderService.updateOrderStatus(
                orderId = order.id.value,
                creatorId = creator.id.value,
                request = UpdateOrderStatusRequest(status = OrderStatus.CANCELLED)
            )

            // Then
            assertNotNull(response)
            assertEquals(OrderStatus.CANCELLED, response.status)
        }
    }

    // ===== 배송 관리 테스트 (4개) =====

    @Test
    fun `배송 정보 업데이트 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)

            val request = UpdateShippingRequest(
                trackingNumber = "1234567890",
                carrier = "CJ대한통운"
            )

            // When
            val response = orderService.updateShippingInfo(
                userId = creator.id.value,
                orderId = order.id.value,
                request = request
            )

            // Then
            assertNotNull(response)
            assertEquals("1234567890", response.trackingNumber)
            assertEquals("CJ대한통운", response.carrier)
        }
    }

    @Test
    fun `배송 상태 조회 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)

            // When
            val response = orderService.getShippingStatus(user.id.value, order.id.value)

            // Then
            assertNotNull(response)
            assertEquals(order.id.value, response.orderId)
        }
    }

    @Test
    fun `배송 상태 업데이트 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)

            // 먼저 배송 정보 설정
            val updateInfo = UpdateShippingRequest(
                trackingNumber = "1234567890",
                carrier = "CJ대한통운"
            )
            orderService.updateShippingInfo(creator.id.value, order.id.value, updateInfo)

            // When - 배송 상태 변경
            val statusRequest = UpdateShippingStatusRequest(
                status = ShippingStatus.IN_TRANSIT
            )
            val response = orderService.updateShippingStatus(
                userId = creator.id.value,
                orderId = order.id.value,
                request = statusRequest
            )

            // Then
            assertNotNull(response)
            assertEquals(ShippingStatus.IN_TRANSIT, response.shippingStatus)
        }
    }

    @Test
    fun `배송 정보 업데이트 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val otherUser = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(otherUser.id.value, product.id.value)

            val request = UpdateShippingRequest(
                trackingNumber = "1234567890",
                carrier = "CJ대한통운"
            )

            // When & Then - 다른 사용자가 배송 정보 업데이트 시도
            assertFailsWith<PermissionDeniedException> {
                orderService.updateShippingInfo(
                    userId = otherUser.id.value,
                    orderId = order.id.value,
                    request = request
                )
            }
        }
    }
}
