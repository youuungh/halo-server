package com.ninezero.service

import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.features.commerce.data.CartRepositoryImpl
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.domain.CartService
import com.ninezero.features.commerce.presentation.models.request.CartRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateCartRequest
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import com.ninezero.features.commerce.presentation.models.response.CartResponse
import io.mockk.coEvery
import io.mockk.spyk
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CartServiceTest {

    private lateinit var cartService: CartService
    private lateinit var cartRepository: CartRepositoryImpl
    private lateinit var productRepository: ProductRepositoryImpl
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var planRepository: SubscriptionPlanRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()
            cartRepository = CartRepositoryImpl()
            productRepository = ProductRepositoryImpl()
            subscriptionRepository = SubscriptionRepositoryImpl()
            planRepository = SubscriptionPlanRepositoryImpl()
            cartService = CartService(cartRepository, productRepository, subscriptionRepository, planRepository)
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

    private suspend fun subscribeToCreatorAtTier(
        userId: Int,
        creatorId: Int,
        tier: SubscriptionPlanTier
    ) {
        query {
            val plan = planRepository.createPlan(
                creatorId = creatorId,
                name = "${tier.name} 플랜",
                tier = tier,
                description = "테스트 플랜",
                price = BigDecimal("5000"),
                benefits = "[\"혜택\"]"
            )

            val now = nowUtc()
            subscriptionRepository.createSubscription(
                userId = userId,
                creatorId = creatorId,
                planId = plan.id.value,
                startedAt = now,
                expiresAt = now.date.plus(DatePeriod(days = 30))
                    .atTime(now.hour, now.minute, now.second, now.nanosecond),
                autoRenew = true
            )
        }
    }

    // ===== 장바구니 추가 테스트 =====

    @Test
    fun `장바구니 추가 실패 - 담긴 수량과 합쳐 최대 수량 초과`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value, stock = 500)
            cartService.addToCart(user.id.value, CartRequest(product.id.value, 99))

            assertFailsWith<InvalidInputException> {
                cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))
            }
            assertEquals(99, cartService.getUserCart(user.id.value).items.single().quantity)
        }
    }

    @Test
    fun `장바구니 추가 실패 - 음수 수량`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val request = CartRequest(productId = product.id.value, quantity = -5)

            // When & Then
            assertFailsWith<InvalidInputException> {
                cartService.addToCart(user.id.value, request)
            }
        }
    }

    @Test
    fun `장바구니 추가 성공 - 멤버십 접근 가능`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                requiredTier = SubscriptionPlanTier.TIER1
            )

            subscribeToCreatorAtTier(
                userId = user.id.value,
                creatorId = creator.id.value,
                tier = SubscriptionPlanTier.TIER1
            )

            val response = cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))

            assertEquals(1, response.totalItems)
            assertEquals(true, response.items.first().product.canAccess)
        }
    }

    // ===== 장바구니 조회 테스트 =====

    @Test
    fun `장바구니 조회 - 총 금액 계산`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product1 = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                price = BigDecimal("10000")
            )
            val product2 = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                price = BigDecimal("20000")
            )

            // When
            cartService.addToCart(user.id.value, CartRequest(product1.id.value, 2))
            cartService.addToCart(user.id.value, CartRequest(product2.id.value, 1))
            val response = cartService.getUserCart(user.id.value)

            // Then
            assertEquals(2, response.totalItems)
            // 10000 * 2 + 20000 * 1 = 40000
            assertEquals(0, BigDecimal(response.totalPrice).compareTo(BigDecimal("40000")), "총 금액이 40000이어야 합니다")
        }
    }

    @Test
    fun `장바구니 항목 수 조회`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val products = TestFixtures.createTestProducts(creator.id.value, 5)

            // When
            products.forEach { product ->
                cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))
            }
            val count = cartService.getCartItemCount(user.id.value)

            // Then
            assertEquals(5, count.count)
        }
    }

    @Test
    fun `장바구니 조회 - canAccess 계산값 반환`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                requiredTier = SubscriptionPlanTier.TIER1
            )

            // 기존 데이터/우회 상황을 가정해 직접 장바구니 삽입
            TestFixtures.addToCart(user.id.value, product.id.value, 1)

            val response = cartService.getUserCart(user.id.value)

            assertEquals(1, response.items.size)
            assertFalse(response.items.first().product.canAccess ?: true)
        }
    }

    // ===== 장바구니 수정 테스트 =====

    @Test
    fun `장바구니 수량 수정 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            val product = TestFixtures.createTestProduct(creator.id.value)
            val addResponse = cartService.addToCart(
                user1.id.value,
                CartRequest(product.id.value, 1)
            )
            val cartId = addResponse.items.first().id

            // When & Then
            val updateRequest = UpdateCartRequest(quantity = 3)
            assertFailsWith<ForbiddenException> {
                cartService.updateCartQuantity(cartId, user2.id.value, updateRequest)
            }
        }
    }

    @Test
    fun `장바구니 수량 수정 후 총 금액 재계산`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                price = BigDecimal("10000"),
                stock = 100
            )
            val addResponse = cartService.addToCart(
                user.id.value,
                CartRequest(product.id.value, 2)
            )
            val cartId = addResponse.items.first().id

            // When
            assertEquals(0, BigDecimal(addResponse.totalPrice).compareTo(BigDecimal("20000")), "총 금액이 20000이어야 합니다")  // 10000 * 2
            val updateRequest = UpdateCartRequest(quantity = 5)
            val response = cartService.updateCartQuantity(cartId, user.id.value, updateRequest)

            // Then
            assertEquals(0, BigDecimal(response.totalPrice).compareTo(BigDecimal("50000")), "총 금액이 50000이어야 합니다")  // 10000 * 5
        }
    }

    // ===== 장바구니 삭제 테스트 =====

    @Test
    fun `장바구니 항목 삭제 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val products = TestFixtures.createTestProducts(creator.id.value, 2)
            cartService.addToCart(user.id.value, CartRequest(products[0].id.value, 1))
            val addResponse = cartService.addToCart(user.id.value, CartRequest(products[1].id.value, 1))
            val cartId = addResponse.items.first { it.product.id == products[0].id.value }.id

            // When
            cartService.removeFromCart(cartId, user.id.value)

            // Then
            val response = cartService.getUserCart(user.id.value)
            assertEquals(1, response.totalItems)
            assertEquals(products[1].id.value, response.items.first().product.id)
        }
    }

    @Test
    fun `장바구니 항목 삭제 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")
            val product = TestFixtures.createTestProduct(creator.id.value)
            val addResponse = cartService.addToCart(
                user1.id.value,
                CartRequest(product.id.value, 1)
            )
            val cartId = addResponse.items.first().id

            // When & Then
            assertFailsWith<ForbiddenException> {
                cartService.removeFromCart(cartId, user2.id.value)
            }
        }
    }

    @Test
    fun `장바구니 항목 삭제 실패 - 존재하지 않는 항목`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When & Then
            assertFailsWith<NotFoundException> {
                cartService.removeFromCart(9999, user.id.value)
            }
        }
    }

    // ===== 장바구니 비우기 테스트 =====

    @Test
    fun `장바구니 전체 비우기 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val products = TestFixtures.createTestProducts(creator.id.value, 3)
            products.forEach { product ->
                cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))
            }

            // When
            cartService.clearCart(user.id.value)

            // Then
            val getResponse = cartService.getUserCart(user.id.value)
            assertEquals(0, getResponse.totalItems)
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `같은 상품을 거의 동시에 담아도 수량 상한을 넘지 않는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val repository = spyk(CartRepositoryImpl())
            val service = CartService(repository, productRepository, subscriptionRepository, planRepository)

            // 첫 요청이 담긴 수량을 읽은 직후 다른 요청이 상한까지 담음
            val interleaved = AtomicBoolean(false)
            var second: Result<CartResponse>? = null
            var secondThread: Thread? = null
            coEvery { repository.findCartItem(user.id.value, product.id.value) } coAnswers {
                val snapshot = callOriginal()
                if (interleaved.compareAndSet(false, true)) {
                    secondThread = thread {
                        second = runCatching {
                            runBlocking { service.addToCart(user.id.value, CartRequest(productId = product.id.value, quantity = 99)) }
                        }
                    }
                    secondThread.join(1500)
                }
                snapshot
            }

            val first = runCatching { service.addToCart(user.id.value, CartRequest(productId = product.id.value, quantity = 1)) }
            secondThread?.join()

            assertTrue(interleaved.get())
            assertEquals(1, listOf(first, second!!).count { it.isSuccess }, "첫 요청 $first / 두 번째 요청 $second")
            assertTrue(service.getUserCart(user.id.value).items.single().quantity <= 99)
        }
    }
}
