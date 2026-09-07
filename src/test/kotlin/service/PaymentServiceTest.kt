package com.ninezero.service

import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.query
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.features.commerce.data.BillingKeyRepositoryImpl
import com.ninezero.features.commerce.data.CartRepositoryImpl
import com.ninezero.features.commerce.data.OrderRepositoryImpl
import com.ninezero.features.commerce.data.PaymentRepositoryImpl
import com.ninezero.features.commerce.domain.PaymentService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * PaymentService 테스트 (Mock 결제/환불 · 결제 상태 조회 · Toss Payments)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentServiceTest {

    private lateinit var paymentService: PaymentService
    private lateinit var orderRepository: OrderRepositoryImpl
    private lateinit var paymentRepository: PaymentRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()
            orderRepository = OrderRepositoryImpl()
            paymentRepository = PaymentRepositoryImpl()
            paymentService = PaymentService(orderRepository, paymentRepository, TossPaymentClient(), CartRepositoryImpl(), BillingKeyRepositoryImpl())
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

    // ===== Mock 결제 테스트 (6개) =====

    @Test
    fun `Mock 결제 처리 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // When
            val message = paymentService.processPayment(order.id.value, buyer.id.value)

            // Then
            assertNotNull(message)

            val payment = query {
                paymentRepository.findByOrderId(order.id.value)
            }
            assertEquals(PaymentStatus.COMPLETED, payment?.status)
        }
    }

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
    fun `Mock 결제 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val otherUser = TestFixtures.createTestUser(email = "other@test.com", username = "other")
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // When & Then
            assertFailsWith<PermissionDeniedException> {
                paymentService.processPayment(order.id.value, otherUser.id.value)
            }
        }
    }

    @Test
    fun `Mock 결제 실패 - 이미 결제된 주문`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // 첫 번째 결제
            paymentService.processPayment(order.id.value, buyer.id.value)

            // When & Then - 두 번째 결제 시도
            assertFailsWith<OrderAlreadyPaidException> {
                paymentService.processPayment(order.id.value, buyer.id.value)
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
            assertFailsWith<Exception> {  // ValidationException 또는 PaymentAmountException 등
                paymentService.processPayment(order.id.value, buyer.id.value)
            }
        }
    }

    // ===== Mock 환불 테스트 (4개) =====

    @Test
    fun `Mock 환불 처리 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // 먼저 결제 완료
            paymentService.processPayment(order.id.value, buyer.id.value)

            // When
            val message = paymentService.refundPayment(order.id.value, buyer.id.value)

            // Then
            assertNotNull(message)

            val payment = query {
                paymentRepository.findByOrderId(order.id.value)
            }
            assertEquals(PaymentStatus.REFUNDED, payment?.status)
            assertNotNull(payment?.refundCompletedAt)
        }
    }

    @Test
    fun `Mock 환불 실패 - 결제 완료되지 않은 주문`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // When & Then - 결제 완료 없이 바로 환불 시도
            assertFailsWith<RefundNotAllowedException> {
                paymentService.refundPayment(order.id.value, buyer.id.value)
            }
        }
    }

    @Test
    fun `Mock 환불 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val otherUser = TestFixtures.createTestUser(email = "other@test.com", username = "other")
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // 결제 완료
            paymentService.processPayment(order.id.value, buyer.id.value)

            // When & Then - 다른 사용자가 환불 시도
            assertFailsWith<PermissionDeniedException> {
                paymentService.refundPayment(order.id.value, otherUser.id.value)
            }
        }
    }

    @Test
    fun `Mock 환불 실패 - 이미 환불된 주문`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // 결제 및 환불 완료
            paymentService.processPayment(order.id.value, buyer.id.value)
            paymentService.refundPayment(order.id.value, buyer.id.value)

            // When & Then - 두 번째 환불 시도
            assertFailsWith<RefundNotAllowedException> {
                paymentService.refundPayment(order.id.value, buyer.id.value)
            }
        }
    }

    // ===== 결제 상태 조회 테스트 (3개) =====

    @Test
    fun `결제 상태 조회 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // When
            val status = paymentService.getPaymentStatus(order.id.value, buyer.id.value)

            // Then
            assertNotNull(status)
            assertEquals(PaymentStatus.PENDING, status)
        }
    }

    @Test
    fun `결제 상태 조회 - 결제 완료 후`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // 결제 완료
            paymentService.processPayment(order.id.value, buyer.id.value)

            // When
            val status = paymentService.getPaymentStatus(order.id.value, buyer.id.value)

            // Then
            assertEquals(PaymentStatus.COMPLETED, status)
        }
    }

    @Test
    fun `결제 상태 조회 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val otherUser = TestFixtures.createTestUser(email = "other@test.com", username = "other")
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // When & Then
            assertFailsWith<PermissionDeniedException> {
                paymentService.getPaymentStatus(order.id.value, otherUser.id.value)
            }
        }
    }

    // ===== Toss Payments 테스트 (3개) =====

    @Test
    fun `Toss Payments 준비 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val buyer = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(buyer.id.value, product.id.value)

            // When
            val data = paymentService.prepareTossPayment(order.id.value, buyer.id.value)

            // Then
            assertNotNull(data)
            assert(data.orderId.isNotBlank())
            assert(data.orderName.isNotBlank())
            assert(data.amount.isNotBlank())
        }
    }

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
            assertFailsWith<PermissionDeniedException> {
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