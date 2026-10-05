package com.ninezero.service

import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.query
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.features.commerce.data.BillingKeyRepositoryImpl
import com.ninezero.features.commerce.data.OrderRepositoryImpl
import com.ninezero.features.commerce.data.PaymentRepositoryImpl
import com.ninezero.features.commerce.domain.OrderService
import com.ninezero.features.commerce.domain.PaymentService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentServiceTest {

    private lateinit var paymentService: PaymentService
    private lateinit var orderRepository: OrderRepositoryImpl
    private lateinit var paymentRepository: PaymentRepositoryImpl
    private lateinit var orderService: OrderService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()
            orderRepository = OrderRepositoryImpl()
            paymentRepository = PaymentRepositoryImpl()
            orderService = mockk(relaxed = true)
            paymentService = PaymentService(orderRepository, paymentRepository, BillingKeyRepositoryImpl(), orderService, mockk<TossPaymentClient>(relaxed = true))
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

    // ===== Mock 결제 테스트 =====

    @Test
    fun `Mock 결제 실패 - 존재하지 않는 주문`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When & Then
            assertFailsWith<OrderNotFoundException> {
                paymentService.processPayment(999, user.id.value)
            }
        }
    }

    @Test
    fun `Mock 결제 실패 - 취소된 주문`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // 결제 취소 처리
            query {
                val payment = paymentRepository.findByOrderId(order.id.value)
                paymentRepository.updateStatus(payment!!.id.value, PaymentStatus.CANCELLED)
            }

            // When & Then
            assertFailsWith<OrderCancelledException> {
                paymentService.processPayment(order.id.value, buyer.id.value)
            }
        }
    }

    @Test
    fun `Mock 결제 실패 - 음수 금액`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value, price = BigDecimal(-1000))
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value, totalPrice = BigDecimal(-1000))

            // When & Then
            assertFailsWith<InvalidInputException> {
                paymentService.processPayment(order.id.value, buyer.id.value)
            }
        }
    }

    @Test
    fun `Mock 결제 완료 시 주문 알림을 보낸다`() {
        runBlocking {
            // Given
            clearMocks(orderService)
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // When
            paymentService.processPayment(order.id.value, buyer.id.value)

            // Then
            verify(exactly = 1) { orderService.notifyOrderPaid(order.id.value) }
            assertEquals(PaymentStatus.COMPLETED, query { paymentRepository.findByOrderId(order.id.value) }?.status)
        }
    }

    // ===== Toss Payments 테스트 =====

    @Test
    fun `Toss Payments 준비 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val otherUser = TestFixtures.createTestUser(email = "other@test.com", username = "other")
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // When & Then
            assertFailsWith<ForbiddenException> {
                paymentService.prepareTossPayment(order.id.value, otherUser.id.value)
            }
        }
    }

    @Test
    fun `Toss Payments 준비 실패 - 이미 결제된 주문`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // 결제 완료
            paymentService.processPayment(order.id.value, buyer.id.value)

            // When & Then - Toss 준비 시도
            assertFailsWith<OrderAlreadyPaidException> {
                paymentService.prepareTossPayment(order.id.value, buyer.id.value)
            }
        }
    }
}
