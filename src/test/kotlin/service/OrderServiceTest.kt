package com.ninezero.service

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.CreatorOrderFilter
import com.ninezero.core.common.config.OrderSource
import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.PaymentProvider
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.config.ShippingStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.database.entities.commerce.CartDao
import com.ninezero.core.database.entities.commerce.OrderDao
import com.ninezero.core.database.entities.commerce.OrderShipmentDao
import com.ninezero.core.database.entities.commerce.OrderShipmentTable
import com.ninezero.core.database.entities.commerce.OrderTable
import com.ninezero.core.database.entities.point.PointDao
import com.ninezero.core.database.entities.point.PointTable
import com.ninezero.core.delivery.DeliveryApiClient
import com.ninezero.core.delivery.DeliveryTrackingItem
import com.ninezero.core.delivery.DeliveryWebhookPayload
import com.ninezero.core.payment.TossCancelResult
import com.ninezero.core.payment.TossConfirmResult
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.core.payment.TossPaymentResponse
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.EmptyCartException
import com.ninezero.core.common.exception.InsufficientStockException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.InvalidPointException
import com.ninezero.core.common.exception.OrderCancelNotAllowedException
import com.ninezero.core.common.exception.OrderCancelledException
import com.ninezero.core.common.exception.OrderNotFoundException
import com.ninezero.core.common.exception.PaymentConfirmFailedException
import com.ninezero.core.common.exception.PaymentResultUnknownException
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.ProductInactiveException
import com.ninezero.core.common.exception.ProductSoldOutException
import com.ninezero.core.common.exception.ShippingStatusUpdateFailedException
import com.ninezero.core.common.exception.SubscriptionRequiredException
import com.ninezero.features.commerce.data.BillingKeyRepositoryImpl
import com.ninezero.features.commerce.data.CartRepositoryImpl
import com.ninezero.features.commerce.data.OrderRepositoryImpl
import com.ninezero.features.commerce.data.PaymentRepositoryImpl
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.data.ReviewRepositoryImpl
import com.ninezero.features.commerce.domain.CartService
import com.ninezero.features.commerce.domain.CreatorSalesService
import com.ninezero.features.commerce.domain.OrderResponseAssembler
import com.ninezero.features.commerce.domain.OrderService
import com.ninezero.features.commerce.domain.OrderShippingService
import com.ninezero.features.commerce.domain.PaymentService
import com.ninezero.features.commerce.presentation.models.request.DirectOrderRequest
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
import com.ninezero.helper.TestFixtures.minusDays
import com.ninezero.helper.TestFixtures.plusDays
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderServiceTest {

    private companion object {
        const val TRACKING_NUMBER = "1234567890"
        const val CARRIER = "CJ대한통운"
        const val COURIER_CODE = "cj"
    }

    private lateinit var orderService: OrderService
    private lateinit var orderShippingService: OrderShippingService
    private lateinit var creatorSalesService: CreatorSalesService
    private lateinit var paymentService: PaymentService
    private lateinit var pointService: PointService
    private lateinit var paymentRepository: PaymentRepositoryImpl
    private lateinit var notificationService: NotificationService
    private lateinit var tossPaymentClient: TossPaymentClient
    private lateinit var productRepository: ProductRepositoryImpl
    private lateinit var cartRepository: CartRepositoryImpl
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var subscriptionPlanRepository: SubscriptionPlanRepositoryImpl
    private lateinit var deliveryApiClient: DeliveryApiClient
    private val backgroundJob = SupervisorJob()  // 알림 등 비동기 작업 완료 대기용

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            // Mock 객체 생성 (NotificationService의 모든 메서드를 relaxed로 처리)
            notificationService = mockk(relaxed = true)
            tossPaymentClient = mockk(relaxed = true)

            // 하위 의존성 초기화
            val pointRepository = PointRepositoryImpl()
            val pointHistoryRepository = PointHistoryRepositoryImpl()
            val orderRepository = OrderRepositoryImpl()
            val reviewRepository = ReviewRepositoryImpl()

            pointService = PointService(
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
            deliveryApiClient = mockk(relaxed = true)
            // 취소 도중 결제 완료를 끼워 넣는 테스트용, 기본은 원본 동작
            paymentRepository = spyk(PaymentRepositoryImpl())

            creatorSalesService = CreatorSalesService(
                orderRepository = orderRepository,
                userRepository = UserRepositoryImpl()
            )

            val orderResponseAssembler = OrderResponseAssembler(
                orderRepository = orderRepository,
                productRepository = productRepository,
                reviewRepository = reviewRepository,
                userRepository = UserRepositoryImpl(),
                paymentRepository = paymentRepository,
                subscriptionRepository = SubscriptionRepositoryImpl()
            )

            // OrderService 초기화
            orderService = OrderService(
                orderRepository = orderRepository,
                cartRepository = cartRepository,
                userRepository = UserRepositoryImpl(),
                paymentRepository = paymentRepository,
                pointRepository = pointRepository,
                notificationService = notificationService,
                pointService = pointService,
                couponRedemptionService = couponRedemptionService,
                subscriptionRepository = subscriptionRepository,
                subscriptionPlanRepository = subscriptionPlanRepository,
                cartService = cartService,
                tossPaymentClient = tossPaymentClient,
                orderResponseAssembler = orderResponseAssembler,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + backgroundJob)
            )

            orderShippingService = OrderShippingService(
                orderRepository = orderRepository,
                paymentRepository = paymentRepository,
                orderResponseAssembler = orderResponseAssembler,
                notificationService = notificationService,
                pointEarnService = pointEarnService,
                deliveryApiClient = deliveryApiClient,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + backgroundJob)
            )
            paymentService = PaymentService(
                orderRepository = orderRepository,
                paymentRepository = paymentRepository,
                tossPaymentClient = tossPaymentClient,
                billingKeyRepository = BillingKeyRepositoryImpl(),
                orderService = orderService
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()
            // PER_CLASS라 mock이 클래스 전체에 공유된다
            clearMocks(deliveryApiClient, paymentRepository)
            every { deliveryApiClient.isEnabled } returns true
            every { deliveryApiClient.toCourierCode(any()) } returns COURIER_CODE
            every { deliveryApiClient.verifyWebhookSignature(any(), any(), any()) } returns true
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    private fun shipmentOf(orderId: Int) =
        OrderShipmentDao.find { OrderShipmentTable.orderId eq orderId }.single()

    private suspend fun shippingStatusOf(orderId: Int): ShippingStatus =
        query { shipmentOf(orderId).shippingStatus }

    /** 송장이 등록된 배송그룹 */
    private suspend fun startShipping(
        orderId: Int,
        shippingStatus: ShippingStatus,
        subscriptionId: String? = null
    ) {
        query {
            val shipment = shipmentOf(orderId)
            shipment.trackingNumber = TRACKING_NUMBER
            shipment.carrier = CARRIER
            shipment.shippingStatus = shippingStatus
            shipment.deliverySubscriptionId = subscriptionId
        }
    }

    private fun trackingItem(orderId: Int, creatorId: Int, currentStatus: String) =
        DeliveryTrackingItem(
            courierCode = COURIER_CODE,
            trackingNumber = TRACKING_NUMBER,
            clientId = "$orderId:$creatorId",
            currentStatus = currentStatus
        )

    /** 추적 결과 하나를 담은 웹훅 */
    private fun stubWebhookItem(orderId: Int, creatorId: Int, currentStatus: String) {
        every { deliveryApiClient.parseWebhookPayload(any()) } returns DeliveryWebhookPayload(
            event = "tracking.polled",
            items = listOf(trackingItem(orderId, creatorId, currentStatus))
        )
    }

    // ===== 동시성·상태 가드 테스트 =====

    @Test
    fun `동시 주문 - 재고 초과 판매 방지`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user1 = TestFixtures.createTestUser(email = "buyer1@example.com", username = "buyer1")
            val user2 = TestFixtures.createTestUser(email = "buyer2@example.com", username = "buyer2")
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value, stock = 1)
            TestFixtures.addToCart(user1.id.value, product.id.value, 1)
            TestFixtures.addToCart(user2.id.value, product.id.value, 1)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구 테헤란로 123",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            val results = listOf(
                async(Dispatchers.IO) { runCatching { orderService.createOrderFromCart(user1.id.value, request) } },
                async(Dispatchers.IO) { runCatching { orderService.createOrderFromCart(user2.id.value, request) } }
            ).awaitAll()

            val successes = results.count { it.isSuccess }
            val stock = query { productRepository.findProductById(product.id.value)!!.stock }
            assertEquals(1, successes)
            assertEquals(0, stock)
        }
    }

    @Test
    fun `동시 부분취소와 주문 - 재고 복구 유실 방지`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val userA = TestFixtures.createTestUser(email = "buyerA@example.com", username = "buyerA")
            val userB = TestFixtures.createTestUser(email = "buyerB@example.com", username = "buyerB")
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value, stock = 2)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구 테헤란로 123",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )

            TestFixtures.addToCart(userA.id.value, product.id.value, 1)
            val orderA = orderService.createOrderFromCart(userA.id.value, request)
            paymentService.processPayment(orderA.id, userA.id.value)
            TestFixtures.addToCart(userB.id.value, product.id.value, 1)

            val results = listOf(
                async(Dispatchers.IO) {
                    runCatching { orderService.cancelOrderGroup(orderA.id, userA.id.value, creator.id.value) }
                },
                async(Dispatchers.IO) {
                    runCatching { orderService.createOrderFromCart(userB.id.value, request) }
                }
            ).awaitAll()

            val stock = query { productRepository.findProductById(product.id.value)!!.stock }
            assertEquals(2, results.count { it.isSuccess })
            assertEquals(1, stock)
        }
    }

    @Test
    fun `동시 주문 취소 - 한 번만 취소 확정`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)

            val results = listOf(
                async(Dispatchers.IO) {
                    runCatching { orderService.cancelOrder(order.id.value, user.id.value) }
                },
                async(Dispatchers.IO) {
                    runCatching { orderService.cancelOrder(order.id.value, user.id.value) }
                }
            ).awaitAll()

            assertEquals(1, results.count { it.isSuccess })
        }
    }

    @Test
    fun `배송 상태 역행 차단 및 완료 재요청 멱등`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value, paymentStatus = PaymentStatus.COMPLETED)

            orderShippingService.updateShippingInfo(
                creator.id.value,
                order.id.value,
                UpdateShippingRequest(trackingNumber = "1234567890", carrier = "CJ대한통운")
            )
            orderShippingService.updateShippingStatus(
                creator.id.value,
                order.id.value,
                UpdateShippingStatusRequest(status = ShippingStatus.DELIVERED)
            )
            val firstDeliveredAt = query {
                OrderRepositoryImpl().findCreatorShipment(order.id.value, creator.id.value)!!.actualDeliveryDate
            }

            // 역행은 차단
            assertFailsWith<ShippingStatusUpdateFailedException> {
                orderShippingService.updateShippingStatus(
                    creator.id.value,
                    order.id.value,
                    UpdateShippingStatusRequest(status = ShippingStatus.SHIPPED)
                )
            }

            // 같은 상태 재요청은 완료 시각을 덮어쓰지 않음
            orderShippingService.updateShippingStatus(
                creator.id.value,
                order.id.value,
                UpdateShippingStatusRequest(status = ShippingStatus.DELIVERED)
            )
            val secondDeliveredAt = query {
                OrderRepositoryImpl().findCreatorShipment(order.id.value, creator.id.value)!!.actualDeliveryDate
            }

            assertEquals(firstDeliveredAt, secondDeliveredAt)
        }
    }

    @Test
    fun `동시 그룹 취소 - 재고 복구 1회`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser(email = "gc@example.com", username = "gcbuyer")
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value, stock = 5)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구 테헤란로 123",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = null,
                couponCodes = null
            )
            TestFixtures.addToCart(user.id.value, product.id.value, 1)
            val order = orderService.createOrderFromCart(user.id.value, request)
            paymentService.processPayment(order.id, user.id.value)

            listOf(
                async(Dispatchers.IO) {
                    runCatching { orderService.cancelOrderGroup(order.id, user.id.value, creator.id.value) }
                },
                async(Dispatchers.IO) {
                    runCatching { orderService.cancelOrderGroup(order.id, user.id.value, creator.id.value) }
                }
            ).awaitAll()

            val stock = query { productRepository.findProductById(product.id.value)!!.stock }
            assertEquals(5, stock)
        }
    }

    @Test
    fun `저장카드 청구 중 주문이 취소되면 늦게 성공한 청구를 토스 취소로 환불한다`() {
        runBlocking {
            clearMocks(tossPaymentClient, notificationService)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value,
                paymentProvider = PaymentProvider.TOSS_BILLING
            )
            query {
                BillingKeyRepositoryImpl().create(
                    userId = user.id.value,
                    customerKey = "ck-late",
                    billingKey = "bk-late",
                    cardCompany = null,
                    cardNumberMasked = null,
                    cardType = null,
                    ownerType = null,
                    authenticatedAt = null
                )
            }
            val charged = TossPaymentResponse(
                paymentKey = "late_billing_key",
                orderId = "ORDER_${order.orderNumber}",
                status = "DONE",
                totalAmount = 10000L
            )
            // 앱이 응답을 못 받고 주문을 취소한 뒤 토스 청구가 성공
            coEvery { tossPaymentClient.chargeBilling(any(), any(), any(), any(), any(), any()) } coAnswers {
                orderService.cancelOrder(order.id.value, user.id.value, "결제 실패")
                TossConfirmResult.Success(charged)
            }
            coEvery { tossPaymentClient.cancel(any(), any(), any(), any()) } returns
                TossCancelResult.Success(charged.copy(status = "CANCELED"))

            assertFailsWith<OrderCancelledException> {
                paymentService.payByBilling(order.id.value, user.id.value)
            }

            val (paymentId, paymentStatus, paymentKey) = query {
                val payment = paymentRepository.findByOrderId(order.id.value)!!
                Triple(payment.id.value, payment.status, payment.paymentKey)
            }
            coVerify(exactly = 1) {
                tossPaymentClient.cancel("late_billing_key", any(), null, "CANCEL_$paymentId")
            }
            assertEquals(PaymentStatus.REFUNDED, paymentStatus)
            assertEquals("late_billing_key", paymentKey)
            assertEquals(OrderStatus.CANCELLED, orderService.getOrderById(order.id.value, user.id.value).status)
            backgroundJob.children.toList().joinAll()
            coVerify(exactly = 0) { notificationService.sendOrderCreatedNotification(any(), any(), any()) }
        }
    }

    @Test
    fun `미결제로 읽고 취소하는 사이 결제가 완료되면 환불 없이 덮지 않고 중단한다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value,
                paymentProvider = PaymentProvider.TOSS_BILLING
            )
            // 취소가 결제 대기를 읽은 직후 저장카드 결제가 먼저 커밋
            var paidMeanwhile = false
            coEvery { paymentRepository.findByOrderId(order.id.value) } coAnswers {
                val snapshot = callOriginal()
                if (!paidMeanwhile) {
                    paidMeanwhile = true
                    query { PaymentRepositoryImpl().updateStatus(snapshot!!.id.value, PaymentStatus.COMPLETED) }
                }
                snapshot
            }
            assertFailsWith<ConflictException> {
                orderService.cancelOrder(order.id.value, user.id.value, "결제 실패")
            }

            clearMocks(paymentRepository)  // 이후 조회는 원본 동작으로
            val paymentStatus = query { paymentRepository.findByOrderId(order.id.value)!!.status }
            assertEquals(PaymentStatus.COMPLETED, paymentStatus)
            assertEquals(OrderStatus.PENDING, orderService.getOrderById(order.id.value, user.id.value).status)
        }
    }

    // ===== 결제 확정 테스트 =====

    /** 토스 결제창 결제 대기 주문, 승인 요청에 쓸 토스 orderId 반환 */
    private suspend fun prepareTossOrder(userId: Int, creatorId: Int): Pair<Int, String> {
        val product = TestFixtures.createTestProduct(creatorId)
        val order = TestFixtures.createTestOrder(
            userId = userId,
            productId = product.id.value,
            paymentProvider = PaymentProvider.TOSS_PAYMENTS
        )
        val prepared = paymentService.prepareTossPayment(order.id.value, userId)
        return order.id.value to prepared.orderId
    }

    @Test
    fun `토스 승인 응답이 유실돼도 재조회로 승인이 확인되면 완료 처리한다`() {
        runBlocking {
            clearMocks(tossPaymentClient, notificationService)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val (orderId, tossOrderId) = prepareTossOrder(user.id.value, creator.id.value)
            coEvery { tossPaymentClient.confirm(any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "TOSS_REQUEST_FAILED", message = "timeout", httpStatus = 0)
            coEvery { tossPaymentClient.findPaymentByOrderId(tossOrderId) } returns
                TossPaymentResponse(paymentKey = "lost_confirm_key", orderId = tossOrderId, status = "DONE", totalAmount = 10000L)

            paymentService.confirmTossPayment(orderId, "lost_confirm_key", 10000L, user.id.value)

            val (status, paymentKey) = query {
                paymentRepository.findByOrderId(orderId)!!.let { it.status to it.paymentKey }
            }
            assertEquals(PaymentStatus.COMPLETED, status)
            assertEquals("lost_confirm_key", paymentKey)
        }
    }

    @Test
    fun `토스 5xx에 재조회도 실패하면 결과 불명으로 두고 주문을 유지한다`() {
        runBlocking {
            clearMocks(tossPaymentClient, notificationService)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val (orderId, _) = prepareTossOrder(user.id.value, creator.id.value)
            coEvery { tossPaymentClient.confirm(any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "PROVIDER_ERROR", message = "일시적인 오류", httpStatus = 500)
            coEvery { tossPaymentClient.findPaymentByOrderId(any()) } throws IllegalStateException("toss down")

            val error = assertFailsWith<PaymentResultUnknownException> {
                paymentService.confirmTossPayment(orderId, "unknown_key", 10000L, user.id.value)
            }

            assertEquals(Errors.Commerce.Payment.PAYMENT_RESULT_UNKNOWN, error.message)
            assertEquals(PaymentStatus.PENDING, query { paymentRepository.findByOrderId(orderId)!!.status })
            assertEquals(OrderStatus.PENDING, orderService.getOrderById(orderId, user.id.value).status)
        }
    }

    @Test
    fun `토스가 승인을 거절하면 결제 실패로 확정한다`() {
        runBlocking {
            clearMocks(tossPaymentClient, notificationService)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val (orderId, _) = prepareTossOrder(user.id.value, creator.id.value)
            coEvery { tossPaymentClient.confirm(any(), any(), any(), any()) } returns
                TossConfirmResult.Failure(code = "REJECT_CARD_COMPANY", message = "카드사 거절", httpStatus = 400)
            coEvery { tossPaymentClient.findPaymentByOrderId(any()) } returns null

            assertFailsWith<PaymentConfirmFailedException> {
                paymentService.confirmTossPayment(orderId, "rejected_key", 10000L, user.id.value)
            }
            assertEquals(PaymentStatus.PENDING, query { paymentRepository.findByOrderId(orderId)!!.status })
        }
    }

    @Test
    fun `토스 승인 중 주문이 취소되면 늦은 승인을 토스 취소로 환불한다`() {
        runBlocking {
            clearMocks(tossPaymentClient, notificationService)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val (orderId, tossOrderId) = prepareTossOrder(user.id.value, creator.id.value)
            val approved = TossPaymentResponse(
                paymentKey = "late_confirm_key",
                orderId = tossOrderId,
                status = "DONE",
                totalAmount = 10000L
            )
            // 정리 작업이 주문을 취소한 뒤 토스 승인이 성공
            coEvery { tossPaymentClient.confirm(any(), any(), any(), any()) } coAnswers {
                orderService.cancelOrder(orderId, user.id.value, "미결제 자동취소")
                TossConfirmResult.Success(approved)
            }
            coEvery { tossPaymentClient.cancel(any(), any(), any(), any()) } returns
                TossCancelResult.Success(approved.copy(status = "CANCELED"))

            assertFailsWith<OrderCancelledException> {
                paymentService.confirmTossPayment(orderId, "late_confirm_key", 10000L, user.id.value)
            }

            val (paymentId, paymentStatus) = query {
                paymentRepository.findByOrderId(orderId)!!.let { it.id.value to it.status }
            }
            coVerify(exactly = 1) {
                tossPaymentClient.cancel("late_confirm_key", any(), null, "CANCEL_$paymentId")
            }
            assertEquals(PaymentStatus.REFUNDED, paymentStatus)
            assertEquals(OrderStatus.CANCELLED, orderService.getOrderById(orderId, user.id.value).status)
        }
    }

    @Test
    fun `환불까지 끝난 주문은 다시 결제하지 않고 토스를 부르지 않는다`() {
        runBlocking {
            clearMocks(tossPaymentClient, notificationService)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value,
                paymentStatus = PaymentStatus.REFUNDED,
                paymentProvider = PaymentProvider.TOSS_PAYMENTS,
                paymentKey = "refunded_key"
            )
            query {
                BillingKeyRepositoryImpl().create(
                    userId = user.id.value,
                    customerKey = "ck-refunded",
                    billingKey = "bk-refunded",
                    cardCompany = null,
                    cardNumberMasked = null,
                    cardType = null,
                    ownerType = null,
                    authenticatedAt = null
                )
            }

            assertFailsWith<OrderCancelledException> { paymentService.processPayment(order.id.value, user.id.value) }
            assertFailsWith<OrderCancelledException> { paymentService.prepareTossPayment(order.id.value, user.id.value) }
            assertFailsWith<OrderCancelledException> {
                paymentService.confirmTossPayment(order.id.value, "new_key", 10000L, user.id.value)
            }
            assertFailsWith<OrderCancelledException> { paymentService.payByBilling(order.id.value, user.id.value) }

            coVerify(exactly = 0) { tossPaymentClient.confirm(any(), any(), any(), any()) }
            coVerify(exactly = 0) { tossPaymentClient.chargeBilling(any(), any(), any(), any(), any(), any()) }
            assertEquals(PaymentStatus.REFUNDED, query { paymentRepository.findByOrderId(order.id.value)!!.status })
        }
    }

    // ===== 주문 생성 테스트 =====

    @Test
    fun `장바구니에서 주문 생성 실패 - 음수 포인트`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)
            TestFixtures.addToCart(user.id.value, product.id.value, 1)

            val request = OrderRequest(
                shippingName = "홍길동",
                shippingPhone = "010-1234-5678",
                shippingAddress = "서울시 강남구 테헤란로 123",
                paymentProvider = PaymentProvider.MOCK,
                memo = null,
                pointsToUse = "-5000",
                couponCodes = null
            )

            assertFailsWith<InvalidPointException> {
                orderService.createOrderFromCart(user.id.value, request)
            }
            val orderCount = query { OrderTable.selectAll().count() }
            assertEquals(0L, orderCount)
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

    private fun directOrderRequest(productId: Int, quantity: Int) = DirectOrderRequest(
        productId = productId,
        quantity = quantity,
        shippingName = "홍길동",
        shippingPhone = "010-1234-5678",
        shippingAddress = "서울시 강남구 테헤란로 123",
        paymentProvider = PaymentProvider.MOCK
    )

    @Test
    fun `바로구매 주문은 결제 뒤에도 장바구니를 건드리지 않는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val cartItem = TestFixtures.addToCart(user.id.value, product.id.value, 3)

            val order = orderService.createDirectOrder(user.id.value, directOrderRequest(product.id.value, 1))
            paymentService.processPayment(order.id, user.id.value)

            assertEquals(1, order.items.single().quantity)
            assertEquals(OrderSource.DIRECT, query { OrderDao.findById(order.id)!!.orderSource })
            assertEquals(3, query { CartDao.findById(cartItem.id.value)?.quantity })
        }
    }

    @Test
    fun `장바구니 주문은 결제 뒤 주문한 상품 줄만 장바구니에서 비운다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val ordered = TestFixtures.addToCart(user.id.value, TestFixtures.createTestProduct(creator.id.value).id.value, 1)
            val kept = TestFixtures.addToCart(user.id.value, TestFixtures.createTestProduct(creator.id.value).id.value, 2)

            val order = orderService.createOrderFromCart(
                user.id.value,
                OrderRequest(
                    shippingName = "홍길동",
                    shippingPhone = "010-1234-5678",
                    shippingAddress = "서울시 강남구 테헤란로 123",
                    paymentProvider = PaymentProvider.MOCK,
                    cartItemIds = listOf(ordered.id.value)
                )
            )
            paymentService.processPayment(order.id, user.id.value)

            assertNull(query { CartDao.findById(ordered.id.value) })
            assertEquals(2, query { CartDao.findById(kept.id.value)?.quantity })
        }
    }

    @Test
    fun `바로구매 실패 - 수량 0·100`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)

            listOf(0, 100).forEach { quantity ->
                assertFailsWith<InvalidInputException> {
                    orderService.createDirectOrder(user.id.value, directOrderRequest(product.id.value, quantity))
                }
            }
        }
    }

    @Test
    fun `바로구매 실패 - 자신의 상품`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            assertFailsWith<InvalidInputException> {
                orderService.createDirectOrder(creator.id.value, directOrderRequest(product.id.value, 1))
            }
        }
    }

    @Test
    fun `잘못된 배송 정보로 주문 생성 실패 - 빈 이름·잘못된 전화번호·빈 주소`() {
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
            assertFailsWith<InvalidInputException> {
                orderService.createOrderFromCart(user.id.value, request)
            }
            assertFailsWith<InvalidInputException> {
                orderService.createOrderFromCart(user.id.value, request.copy(shippingName = "홍길동", shippingPhone = "123"))
            }
            assertFailsWith<InvalidInputException> {
                orderService.createOrderFromCart(user.id.value, request.copy(shippingName = "홍길동", shippingAddress = ""))
            }
        }
    }

    @Test
    fun `주문 생성만으로는 주문 알림을 보내지 않는다`() {
        runBlocking {
            // Given
            clearMocks(notificationService)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)
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

            // When
            orderService.createOrderFromCart(user.id.value, request)

            // Then 결제 전이라 구매자·판매자 모두 알림 없음
            backgroundJob.children.toList().joinAll()
            coVerify(exactly = 0) { notificationService.sendOrderCreatedNotification(any(), any(), any()) }
            coVerify(exactly = 0) { notificationService.sendOrderReceivedNotification(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `결제 완료 알림은 구매자와 판매자에게 보낸다`() {
        runBlocking {
            // Given
            clearMocks(notificationService)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)
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
            val order = orderService.createOrderFromCart(user.id.value, request)

            // When
            orderService.notifyOrderPaid(order.id)

            // Then 비동기 발송이라 대기
            coVerify(timeout = 3000) {
                notificationService.sendOrderCreatedNotification(user.id.value, order.id, order.orderNumber)
            }
            coVerify(timeout = 3000) {
                notificationService.sendOrderReceivedNotification(creator.id.value, user.id.value, order.id, order.orderNumber)
            }
        }
    }

    // ===== 주문 조회 테스트 =====

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
    fun `내 주문 목록은 결제 대기·결제 전 취소를 쿼리에서 빼 첫 페이지가 비지 않는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            TestFixtures.createTestOrder(user.id.value, product.id.value, paymentStatus = PaymentStatus.COMPLETED, orderNumber = "ORD-PAID")
            // 더 최근의 이탈 주문이 한 페이지를 채움
            TestFixtures.createTestOrder(user.id.value, product.id.value, orderNumber = "ORD-PENDING-1")
            TestFixtures.createTestOrder(user.id.value, product.id.value, orderNumber = "ORD-PENDING-2")
            TestFixtures.createTestOrder(user.id.value, product.id.value, paymentStatus = PaymentStatus.CANCELLED, orderNumber = "ORD-CANCELLED")

            val response = orderService.getMyOrders(user.id.value, limit = 2)

            assertEquals(listOf("ORD-PAID"), response.items.map { it.orderNumber })
            assertEquals(false, response.hasNext)
        }
    }

    @Test
    fun `내 주문 목록은 토스 거래를 시작한 결제 대기 주문을 포함한다(결제 확인 중)`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            // 토스 거래 id가 있는 결제 대기 = 결과 불명, 거래 전 이탈은 계속 제외
            TestFixtures.createTestOrder(user.id.value, product.id.value, paymentProvider = PaymentProvider.TOSS_PAYMENTS, orderNumber = "ORD-CONFIRMING")
            val abandoned = TestFixtures.createTestOrder(user.id.value, product.id.value, paymentProvider = PaymentProvider.TOSS_PAYMENTS, orderNumber = "ORD-ABANDONED")
            query { paymentRepository.findByOrderId(abandoned.id.value)!!.transactionId = null }

            val response = orderService.getMyOrders(user.id.value, limit = 10)

            assertEquals(listOf("ORD-CONFIRMING"), response.items.map { it.orderNumber })
            assertEquals(false, response.hasNext)
        }
    }

    @Test
    fun `결제 금액 0원 주문은 PG 없이 바로 결제 완료되고 장바구니에서 빠진다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            // 무료배송 기준 이상 + 같은 금액 쿠폰 = 0원
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value, price = BigDecimal(60000))
            query {
                val coupon = CouponRepositoryImpl().create(
                    creatorId = creator.id.value,
                    code = "FREE0",
                    name = "전액 쿠폰",
                    description = null,
                    type = CouponType.FIXED_AMOUNT,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = BigDecimal("60000"),
                    minOrderAmount = BigDecimal("0"),
                    maxDiscountAmount = null,
                    totalQuantity = 10,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = nowUtc().minusDays(1),
                    endDate = nowUtc().plusDays(30)
                )
                UserCouponRepositoryImpl().create(user.id.value, coupon.id.value, nowUtc().plusDays(30))
            }
            TestFixtures.addToCart(user.id.value, product.id.value, 1)

            val order = orderService.createOrderFromCart(
                user.id.value,
                OrderRequest(
                    shippingName = "홍길동",
                    shippingPhone = "010-1234-5678",
                    shippingAddress = "서울시 강남구 테헤란로 123",
                    paymentProvider = PaymentProvider.NONE,
                    memo = null,
                    pointsToUse = null,
                    couponCodes = listOf("FREE0")
                )
            )

            assertEquals(0, BigDecimal(order.totalPrice).signum())
            assertEquals(PaymentStatus.COMPLETED, order.paymentStatus)
            assertTrue(query { cartRepository.findUserCart(user.id.value) }.isEmpty())
            coVerify(timeout = 3000) {
                notificationService.sendOrderCreatedNotification(user.id.value, order.id, order.orderNumber)
            }
        }
    }

    @Test
    fun `판매자 주문 목록은 결제 대기를 쿼리에서 빼되 해제 사전조건 개수는 미결제도 센다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            TestFixtures.createTestOrder(buyer.id.value, product.id.value, paymentStatus = PaymentStatus.COMPLETED, orderNumber = "ORD-PAID")
            TestFixtures.createTestOrder(buyer.id.value, product.id.value, orderNumber = "ORD-PENDING-1")
            TestFixtures.createTestOrder(buyer.id.value, product.id.value, orderNumber = "ORD-PENDING-2")

            val response = orderService.getCreatorOrders(creator.id.value, limit = 2)

            assertEquals(listOf("ORD-PAID"), response.items.map { it.orderNumber })
            assertEquals(false, response.hasNext)
            // 크리에이터 해제 사전조건은 미결제 주문도 진행 중으로 셈
            assertEquals(3, query { OrderRepositoryImpl().countCreatorOrders(creator.id.value, CreatorOrderFilter.PENDING) })
        }
    }

    // ===== 주문 취소 테스트 =====

    @Test
    fun `탈퇴로 보유 쿠폰이 지워져도 쿠폰 쓴 미결제 주문 취소가 끝까지 진행된다`() {
        runBlocking {
            // Given — 쿠폰을 쓴 결제 전 주문
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value, stock = 5)
            val userCouponRepository = UserCouponRepositoryImpl()
            query {
                val coupon = CouponRepositoryImpl().create(
                    creatorId = creator.id.value,
                    code = "WITHDRAW1",
                    name = "탈퇴 테스트 쿠폰",
                    description = null,
                    type = CouponType.FIXED_AMOUNT,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = BigDecimal("1000"),
                    minOrderAmount = BigDecimal("0"),
                    maxDiscountAmount = null,
                    totalQuantity = 10,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = nowUtc().minusDays(1),
                    endDate = nowUtc().plusDays(30)
                )
                userCouponRepository.create(user.id.value, coupon.id.value, nowUtc().plusDays(30))
            }
            TestFixtures.addToCart(user.id.value, product.id.value, 1)
            val order = orderService.createOrderFromCart(
                user.id.value,
                OrderRequest(
                    shippingName = "홍길동",
                    shippingPhone = "010-1234-5678",
                    shippingAddress = "서울시 강남구 테헤란로 123",
                    paymentProvider = PaymentProvider.MOCK,
                    memo = null,
                    pointsToUse = null,
                    couponCodes = listOf("WITHDRAW1")
                )
            )

            // 탈퇴 정리와 같은 방식으로 보유 쿠폰 삭제
            query { userCouponRepository.deleteAllByUser(user.id.value) }

            // When — 예전에는 쿠폰 복구에서 404로 롤백돼 영구히 멈췄음
            val response = orderService.cancelOrder(order.id, user.id.value)

            // Then
            assertEquals(OrderStatus.CANCELLED, response.status)
            val stock = query { productRepository.findProductById(product.id.value)!!.stock }
            assertEquals(5, stock)
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
            assertFailsWith<ForbiddenException> {
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
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value, paymentStatus = PaymentStatus.COMPLETED)

            // When
            val response = orderService.updateOrderStatus(
                orderId = order.id.value,
                creatorId = creator.id.value,
                request = UpdateOrderStatusRequest(status = OrderStatus.CANCELLED)
            )

            // Then
            assertEquals(OrderStatus.CANCELLED, response.status)
        }
    }

    @Test
    fun `결제 전 주문은 판매자별 취소를 거부하고 전체 취소만 받는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)

            assertFailsWith<OrderCancelNotAllowedException> {
                orderService.cancelOrderGroup(order.id.value, user.id.value, creator.id.value)
            }
            assertFailsWith<OrderCancelNotAllowedException> {
                orderService.updateOrderStatus(order.id.value, creator.id.value, UpdateOrderStatusRequest(status = OrderStatus.CANCELLED))
            }
            assertEquals(OrderStatus.PENDING, orderService.getOrderById(order.id.value, user.id.value).status)

            assertEquals(OrderStatus.CANCELLED, orderService.cancelOrder(order.id.value, user.id.value).status)
        }
    }

    @Test
    fun `포인트 쓴 주문의 판매자별 환불액은 토스 요청액과 기록이 같은 원 단위다`() {
        runBlocking {
            clearMocks(tossPaymentClient)
            val user = TestFixtures.createTestUser()
            val creators = (1..3).map { TestFixtures.createTestCreator(email = "split$it@example.com", username = "split$it") }
            creators.forEach { creator ->
                TestFixtures.addToCart(user.id.value, TestFixtures.createTestProduct(creator.id.value).id.value, 1)
            }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 999, "적립") }
            val order = orderService.createOrderFromCart(
                user.id.value,
                OrderRequest(
                    shippingName = "홍길동",
                    shippingPhone = "010-1234-5678",
                    shippingAddress = "서울시 강남구 테헤란로 123",
                    paymentProvider = PaymentProvider.TOSS_PAYMENTS,
                    memo = null,
                    pointsToUse = "1000",
                    couponCodes = null
                )
            )
            query {
                val payment = paymentRepository.findByOrderId(order.id)!!
                paymentRepository.updatePaymentInfo(payment.id.value, "split_key", null)
                paymentRepository.updateStatus(payment.id.value, PaymentStatus.COMPLETED)
            }
            coEvery { tossPaymentClient.cancel(any(), any(), any(), any()) } returns TossCancelResult.Success(
                TossPaymentResponse(paymentKey = "split_key", orderId = order.orderNumber, status = "PARTIAL_CANCELED", totalAmount = 38000L)
            )

            orderService.cancelOrderGroup(order.id, user.id.value, creators.first().id.value)

            val requested = mutableListOf<Long?>()
            coVerify(exactly = 1) { tossPaymentClient.cancel("split_key", any(), captureNullable(requested), any()) }
            val recorded = query {
                OrderShipmentDao.find {
                    (OrderShipmentTable.orderId eq order.id) and (OrderShipmentTable.creatorId eq creators.first().id.value)
                }.single().refundAmount!!
            }
            assertEquals(0, BigDecimal(requested.single()!!).compareTo(recorded), "토스 $requested / 기록 $recorded")
        }
    }

    @Test
    fun `포인트 쓴 주문을 그룹별로 취소하면 환불 포인트는 1P 단위이고 합계는 사용액과 같다`() {
        runBlocking {
            clearMocks(tossPaymentClient)
            val user = TestFixtures.createTestUser()
            val creators = (1..3).map { TestFixtures.createTestCreator(email = "pt$it@example.com", username = "pt$it") }
            creators.forEach { creator ->
                TestFixtures.addToCart(user.id.value, TestFixtures.createTestProduct(creator.id.value).id.value, 1)
            }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 998, "적립") }
            val order = orderService.createOrderFromCart(
                user.id.value,
                OrderRequest(
                    shippingName = "홍길동",
                    shippingPhone = "010-1234-5678",
                    shippingAddress = "서울시 강남구 테헤란로 123",
                    paymentProvider = PaymentProvider.TOSS_PAYMENTS,
                    memo = null,
                    pointsToUse = "1000",
                    couponCodes = null
                )
            )
            query {
                val payment = paymentRepository.findByOrderId(order.id)!!
                paymentRepository.updatePaymentInfo(payment.id.value, "pt_key", null)
                paymentRepository.updateStatus(payment.id.value, PaymentStatus.COMPLETED)
            }
            coEvery { tossPaymentClient.cancel(any(), any(), any(), any()) } returns TossCancelResult.Success(
                TossPaymentResponse(paymentKey = "pt_key", orderId = order.orderNumber, status = "PARTIAL_CANCELED", totalAmount = 38000L)
            )

            val balances = creators.map { creator ->
                orderService.cancelOrderGroup(order.id, user.id.value, creator.id.value)
                query { PointDao.find { PointTable.userId eq user.id.value }.single().balance }
            }

            // 333.33처럼 소수로 환불되면 전액 사용 주문이 잔액 부족으로 거절됨
            balances.forEach { assertTrue(it.stripTrailingZeros().scale() <= 0, "잔액 $it") }
            assertEquals(0, balances.last().compareTo(BigDecimal("1000")))
        }
    }

    @Test
    fun `결제 전 주문은 판매자가 확정하거나 송장을 등록할 수 없다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)

            assertFailsWith<ConflictException> {
                orderService.updateOrderStatus(order.id.value, creator.id.value, UpdateOrderStatusRequest(status = OrderStatus.CONFIRMED))
            }
            assertFailsWith<ConflictException> {
                orderShippingService.updateShippingInfo(creator.id.value, order.id.value, UpdateShippingRequest(TRACKING_NUMBER, CARRIER))
            }
            assertNull(query { shipmentOf(order.id.value).trackingNumber })
        }
    }

    @Test
    fun `토스가 이미 전액 취소한 결제는 로컬 취소를 이어서 끝낸다`() {
        runBlocking {
            clearMocks(tossPaymentClient)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value,
                paymentStatus = PaymentStatus.COMPLETED,
                paymentProvider = PaymentProvider.TOSS_PAYMENTS,
                paymentKey = "already_cancelled_key"
            )
            coEvery { tossPaymentClient.cancel(any(), any(), any(), any()) } returns TossCancelResult.Failure(
                code = "ALREADY_CANCELED_PAYMENT",
                message = "이미 취소된 결제 입니다",
                httpStatus = 400
            )
            coEvery { tossPaymentClient.findPaymentByKey("already_cancelled_key") } returns TossPaymentResponse(
                paymentKey = "already_cancelled_key",
                orderId = order.orderNumber,
                status = "CANCELED",
                totalAmount = 10000L
            )

            val response = orderService.cancelOrder(order.id.value, user.id.value)

            assertEquals(OrderStatus.CANCELLED, response.status)
        }
    }

    @Test
    fun `토스 취소 실패이고 실제로도 미취소면 취소를 중단한다`() {
        runBlocking {
            clearMocks(tossPaymentClient)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value,
                paymentStatus = PaymentStatus.COMPLETED,
                paymentProvider = PaymentProvider.TOSS_PAYMENTS,
                paymentKey = "not_cancelled_key"
            )
            coEvery { tossPaymentClient.cancel(any(), any(), any(), any()) } returns TossCancelResult.Failure(
                code = "PROVIDER_ERROR",
                message = "일시적인 오류가 발생했습니다",
                httpStatus = 500
            )
            coEvery { tossPaymentClient.findPaymentByKey("not_cancelled_key") } returns TossPaymentResponse(
                paymentKey = "not_cancelled_key",
                orderId = order.orderNumber,
                status = "DONE",
                totalAmount = 10000L
            )

            assertFailsWith<PaymentConfirmFailedException> {
                orderService.cancelOrder(order.id.value, user.id.value)
            }
        }
    }

    @Test
    fun `토스 취소 사유는 사용자 입력과 무관하게 고정값으로 보낸다`() {
        runBlocking {
            clearMocks(tossPaymentClient)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value,
                paymentStatus = PaymentStatus.COMPLETED,
                paymentProvider = PaymentProvider.TOSS_PAYMENTS,
                paymentKey = "fixed_reason_key"
            )
            coEvery { tossPaymentClient.cancel(any(), any(), any(), any()) } returns TossCancelResult.Success(
                TossPaymentResponse(
                    paymentKey = "fixed_reason_key",
                    orderId = order.orderNumber,
                    status = "CANCELED",
                    totalAmount = 10000L
                )
            )

            orderService.cancelOrder(order.id.value, user.id.value, "색상이 마음에 들지 않아요")

            coVerify(exactly = 1) {
                tossPaymentClient.cancel("fixed_reason_key", "주문 취소", any(), any())
            }
        }
    }

    @Test
    fun `결제완료 전체 취소 중 토스 환불 뒤 송장이 등록돼도 취소를 끝낸다`() {
        runBlocking {
            clearMocks(tossPaymentClient)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value,
                paymentStatus = PaymentStatus.COMPLETED,
                paymentProvider = PaymentProvider.TOSS_PAYMENTS,
                paymentKey = "ship_race_key"
            )
            // 토스 환불 응답을 기다리는 사이 판매자가 송장 등록
            coEvery { tossPaymentClient.cancel(any(), any(), any(), any()) } coAnswers {
                orderShippingService.updateShippingInfo(
                    creator.id.value, order.id.value,
                    UpdateShippingRequest(trackingNumber = "1234567890", carrier = "CJ대한통운")
                )
                TossCancelResult.Success(
                    TossPaymentResponse(paymentKey = "ship_race_key", orderId = order.orderNumber, status = "CANCELED", totalAmount = 10000L)
                )
            }

            val response = orderService.cancelOrder(order.id.value, user.id.value)

            assertEquals(OrderStatus.CANCELLED, response.status)
            val (paymentStatus, trackingNumber) = query {
                paymentRepository.findByOrderId(order.id.value)!!.status to
                    shipmentOf(order.id.value).trackingNumber
            }
            assertEquals(PaymentStatus.REFUNDED, paymentStatus)
            assertNull(trackingNumber)  // 취소된 주문에 배송 추적이 남지 않음

            val detail = orderService.getOrderById(order.id.value, user.id.value)
            listOf(response, detail).forEach {
                assertNull(it.trackingNumber)
                assertNull(it.carrier)
                assertNull(it.estimatedDeliveryDate)
                assertEquals(ShippingStatus.PREPARING, it.shippingStatus)
                assertNull(it.creatorGroups.single().trackingNumber)
            }
        }
    }

    @Test
    fun `결제완료 주문 동시 전체 취소 - 한 번만 확정`() {
        runBlocking {
            clearMocks(tossPaymentClient)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value,
                paymentStatus = PaymentStatus.COMPLETED,
                paymentProvider = PaymentProvider.TOSS_PAYMENTS,
                paymentKey = "double_cancel_key"
            )
            coEvery { tossPaymentClient.cancel(any(), any(), any(), any()) } returns TossCancelResult.Success(
                TossPaymentResponse(paymentKey = "double_cancel_key", orderId = order.orderNumber, status = "CANCELED", totalAmount = 10000L)
            )

            val results = listOf(
                async(Dispatchers.IO) { runCatching { orderService.cancelOrder(order.id.value, user.id.value) } },
                async(Dispatchers.IO) { runCatching { orderService.cancelOrder(order.id.value, user.id.value) } }
            ).awaitAll()

            assertEquals(1, results.count { it.isSuccess })
            val stock = query { productRepository.findProductById(product.id.value)!!.stock }
            assertEquals(101, stock)  // 기본 재고 100 + 주문 수량 1, 복구 1회
        }
    }

    @Test
    fun `미결제 주문 취소는 판매자에게 알리지 않는다`() {
        runBlocking {
            clearMocks(notificationService)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)

            orderService.cancelOrder(order.id.value, user.id.value, "미결제 자동취소")

            coVerify(exactly = 0) { notificationService.sendOrderCancelledNotification(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `결제된 주문 취소는 판매자에게 알린다`() {
        runBlocking {
            clearMocks(notificationService)
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value, paymentStatus = PaymentStatus.COMPLETED)

            orderService.cancelOrder(order.id.value, user.id.value)

            coVerify(exactly = 1, timeout = 3000) {
                notificationService.sendOrderCancelledNotification(creator.id.value, user.id.value, order.id.value, true)
            }
        }
    }

    // ===== 매출 통계 테스트 =====

    @Test
    fun `월 매출은 한국 시간 달 경계로 센다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            fun kstToUtc(dateTime: LocalDateTime) =
                dateTime.toInstant(TimeZone.of("Asia/Seoul")).toLocalDateTime(TimeZone.UTC)

            // 결제된 주문의 주문 시각을 한국 시간으로 지정
            val orderedAtKst = mapOf(
                "ORD-OCT-3AM" to LocalDateTime(2026, 10, 1, 3, 0),  // UTC로는 9월 30일
                "ORD-OCT-FRACTION" to LocalDateTime(2026, 10, 1, 8, 59, 59, 500_000_000),  // UTC 9월 말일 23:59:59.5
                "ORD-SEP-LAST" to LocalDateTime(2026, 9, 30, 23, 59, 59, 500_000_000)
            )
            orderedAtKst.forEach { (orderNumber, kst) ->
                val order = TestFixtures.createTestOrder(
                    user.id.value,
                    product.id.value,
                    paymentStatus = PaymentStatus.COMPLETED,
                    orderNumber = orderNumber
                )
                query { OrderTable.update({ OrderTable.id eq order.id }) { it[createdAt] = kstToUtc(kst) } }
            }

            val october = creatorSalesService.getCreatorMonthlySales(creator.id.value, "2026-10")
            val september = creatorSalesService.getCreatorMonthlySales(creator.id.value, "2026-09")

            assertEquals(2, october.orderCount)
            assertEquals(1, september.orderCount)
            // 6개월 추이도 같은 달 경계, 마지막 두 칸이 9월·10월
            assertEquals(listOf("10000", "20000"), october.monthlyTrend.takeLast(2).map { it.revenue })
        }
    }

    // ===== 배송 관리 테스트 =====

    @Test
    fun `추적 등록에 실패한 배송 중 그룹은 재시도 작업이 다시 등록한다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)
            // 송장은 입력됐지만 택배사 API 실패로 추적 구독이 없는 상태
            startShipping(order.id.value, ShippingStatus.SHIPPED)
            coEvery { deliveryApiClient.subscribeTracking(any(), any(), any()) } returns "req-retry-1"

            val registered = orderShippingService.retryMissingTrackingSubscriptions()

            assertEquals(1, registered)
            coVerify {
                deliveryApiClient.subscribeTracking(
                    COURIER_CODE, TRACKING_NUMBER, "${order.id.value}:${creator.id.value}"
                )
            }
            val subscriptionId = query { shipmentOf(order.id.value).deliverySubscriptionId }
            assertEquals("req-retry-1", subscriptionId)
        }
    }

    @Test
    fun `배송완료 웹훅 적립은 주문 뒤 삭제된 상품 금액도 포함한다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            // 항목 10,000원 × 1, 티어 스냅샷 없음 → FREE 1% = 100
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value, paymentStatus = PaymentStatus.COMPLETED)
            startShipping(order.id.value, ShippingStatus.OUT_FOR_DELIVERY)
            query { productRepository.deleteProduct(product.id.value) }  // 배송 중 판매자가 상품 삭제
            stubWebhookItem(order.id.value, creator.id.value, "DELIVERED")

            assertEquals("processed", orderShippingService.handleDeliveryWebhook("{}", "sha256=test", "0"))

            val balance = query { PointRepositoryImpl().findByUserId(user.id.value)?.balance } ?: BigDecimal.ZERO
            assertEquals(0, BigDecimal("100").compareTo(balance))
        }
    }

    @Test
    fun `문서 형식의 배송완료 웹훅은 clientId의 배송그룹을 배송완료로 바꾼다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value, paymentStatus = PaymentStatus.COMPLETED)
            startShipping(order.id.value, ShippingStatus.OUT_FOR_DELIVERY, subscriptionId = "req-doc-1")
            // 택배API 문서의 tracking.polled 본문 형식
            val rawBody = """
                {
                  "event": "tracking.polled",
                  "requestId": "req-doc-1",
                  "timestamp": "2026-01-01T12:00:00.000Z",
                  "summary": { "total": 1, "delivered": 1, "active": 0, "hasChanges": true },
                  "items": [
                    {
                      "courierCode": "cj",
                      "trackingNumber": "1234567890",
                      "clientId": "${order.id.value}:${creator.id.value}",
                      "currentStatus": "DELIVERED",
                      "previousStatus": "OUT_FOR_DELIVERY",
                      "hasChanged": true,
                      "isDelivered": true,
                      "trackingData": { "courierName": "CJ대한통운" }
                    }
                  ],
                  "metadata": { "source": "halo" }
                }
            """.trimIndent()
            val realClient = DeliveryApiClient()
            every { deliveryApiClient.parseWebhookPayload(any()) } answers { realClient.parseWebhookPayload(firstArg()) }

            assertEquals("processed", orderShippingService.handleDeliveryWebhook(rawBody, "sha256=test", "0"))

            val (shippingStatus, status) = query {
                shipmentOf(order.id.value).let { it.shippingStatus to it.status }
            }
            assertEquals(ShippingStatus.DELIVERED, shippingStatus)
            assertEquals(OrderStatus.DELIVERED, status)
        }
    }

    @Test
    fun `같은 송장번호를 쓰는 다른 배송그룹은 웹훅이 바꾸지 않는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val firstOrder = TestFixtures.createTestOrder(user.id.value, product.id.value)
            val secondOrder = TestFixtures.createTestOrder(user.id.value, product.id.value)
            // 먼저 만든 그룹이 송장번호 조회에서 먼저 잡히는 상태
            listOf(firstOrder, secondOrder).forEachIndexed { index, target ->
                startShipping(target.id.value, ShippingStatus.SHIPPED, subscriptionId = "req-same-$index")
            }
            stubWebhookItem(secondOrder.id.value, creator.id.value, "IN_TRANSIT")

            assertEquals("processed", orderShippingService.handleDeliveryWebhook("{}", "sha256=test", "0"))

            val statuses = listOf(firstOrder, secondOrder).map { shippingStatusOf(it.id.value) }
            assertEquals(listOf(ShippingStatus.SHIPPED, ShippingStatus.IN_TRANSIT), statuses)
        }
    }

    @Test
    fun `서명 검증에 실패한 웹훅은 예외 없이 무시한다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value)
            startShipping(order.id.value, ShippingStatus.SHIPPED, subscriptionId = "req-forged-1")
            every { deliveryApiClient.verifyWebhookSignature(any(), any(), any()) } returns false
            stubWebhookItem(order.id.value, creator.id.value, "DELIVERED")

            // 실패 응답이 쌓이면 제공사가 엔드포인트를 끄므로 거절도 200
            assertEquals("ignored", orderShippingService.handleDeliveryWebhook("{}", "sha256=forged", "0"))

            assertEquals(ShippingStatus.SHIPPED, shippingStatusOf(order.id.value))
        }
    }

    @Test
    fun `웹훅을 놓친 배송완료는 보정 작업이 구독 상세로 반영한다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(user.id.value, product.id.value, paymentStatus = PaymentStatus.COMPLETED)
            startShipping(order.id.value, ShippingStatus.IN_TRANSIT, subscriptionId = "req-reconcile-1")
            coEvery { deliveryApiClient.getTrackingItems(any()) } returns emptyList()
            coEvery { deliveryApiClient.getTrackingItems("req-reconcile-1") } returns listOf(
                trackingItem(order.id.value, creator.id.value, "DELIVERED")
            )

            assertEquals(1, orderShippingService.reconcileTrackingSubscriptions())

            assertEquals(ShippingStatus.DELIVERED, shippingStatusOf(order.id.value))
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
            startShipping(order.id.value, ShippingStatus.SHIPPED)

            // When
            val response = orderShippingService.getShippingStatus(user.id.value, order.id.value)

            // Then
            assertEquals(order.id.value, response.orderId)
            assertEquals(ShippingStatus.SHIPPED, response.shippingStatus)
            assertEquals(TRACKING_NUMBER, response.trackingNumber)
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
            assertFailsWith<ForbiddenException> {
                orderShippingService.updateShippingInfo(
                    userId = otherUser.id.value,
                    orderId = order.id.value,
                    request = request
                )
            }
        }
    }
}
