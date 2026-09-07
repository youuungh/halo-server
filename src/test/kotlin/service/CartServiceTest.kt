package com.ninezero.service

import com.ninezero.core.common.config.ProductStatus
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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * CartService 테스트
 *
 * 테스트 케이스: 20개
 * - 장바구니 추가: 7개
 * - 장바구니 조회: 3개
 * - 장바구니 수정: 5개
 * - 장바구니 삭제: 3개
 * - 장바구니 비우기: 2개
 */
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

    // ===== 장바구니 추가 테스트 (7개) =====

    @Test
    fun `장바구니 추가 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)

            val request = CartRequest(
                productId = product.id.value,
                quantity = 2
            )

            // When
            val response = cartService.addToCart(user.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals(1, response.totalItems)
            assertEquals(2, response.items.first().quantity)
            assertEquals(product.id.value, response.items.first().product.id)
        }
    }

    @Test
    fun `장바구니 추가 성공 - 여러 상품`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val products = TestFixtures.createTestProducts(creator.id.value, 3)

            // When
            products.forEach { product ->
                cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))
            }

            // Then
            val response = cartService.getUserCart(user.id.value)
            assertNotNull(response)
            assertEquals(3, response.totalItems)
            assertEquals(3, response.items.size)
        }
    }

    @Test
    fun `장바구니 추가 실패 - 존재하지 않는 상품`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val request = CartRequest(productId = 9999, quantity = 1)

            // When & Then
            assertFailsWith<NotFoundException> {
                cartService.addToCart(user.id.value, request)
            }
        }
    }

    @Test
    fun `장바구니 추가 실패 - 자신의 상품`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val request = CartRequest(productId = product.id.value, quantity = 1)

            // When & Then
            assertFailsWith<InvalidInputException> {
                cartService.addToCart(creator.id.value, request)
            }
        }
    }

    @Test
    fun `장바구니 추가 실패 - 재고 부족`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 5
            )
            val request = CartRequest(productId = product.id.value, quantity = 10)

            // When & Then
            assertFailsWith<InsufficientStockException> {
                cartService.addToCart(user.id.value, request)
            }
        }
    }

    @Test
    fun `장바구니 추가 실패 - 수량 0개`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val request = CartRequest(productId = product.id.value, quantity = 0)

            // When & Then
            assertFailsWith<InvalidInputException> {
                cartService.addToCart(user.id.value, request)
            }
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
    fun `장바구니 추가 실패 - 판매 중단 상품`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                status = ProductStatus.DISCONTINUED
            )

            assertFailsWith<ProductInactiveException> {
                cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))
            }
        }
    }

    @Test
    fun `장바구니 추가 실패 - 품절 상품`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 0,
                status = ProductStatus.SOLD_OUT
            )

            assertFailsWith<ProductSoldOutException> {
                cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))
            }
        }
    }

    @Test
    fun `장바구니 추가 실패 - 멤버십 접근 불가`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                requiredTier = SubscriptionPlanTier.TIER1
            )

            assertFailsWith<SubscriptionRequiredException> {
                cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))
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

    // ===== 장바구니 조회 테스트 (3개) =====

    @Test
    fun `장바구니 조회 - 빈 장바구니`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When
            val response = cartService.getUserCart(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(0, response.totalItems)
            assertEquals(0, response.items.size)
            assertEquals("0", response.totalPrice)
        }
    }

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
            assertNotNull(response)
            assertEquals(2, response.totalItems)
            // 10000 * 2 + 20000 * 1 = 40000
            assertEquals(BigDecimal(response.totalPrice).compareTo(BigDecimal("40000")), 0, "총 금액이 40000이어야 합니다")
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
            assertEquals(5, count["count"])
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

    // ===== 장바구니 수정 테스트 (5개) =====

    @Test
    fun `장바구니 수량 수정 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 100
            )
            val addResponse = cartService.addToCart(
                user.id.value,
                CartRequest(product.id.value, 2)
            )
            val cartId = addResponse.items.first().id

            // When
            val updateRequest = UpdateCartRequest(quantity = 5)
            val response = cartService.updateCartQuantity(cartId, user.id.value, updateRequest)

            // Then
            assertNotNull(response)
            assertEquals(1, response.totalItems)
            assertEquals(5, response.items.first().quantity)
        }
    }

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
    fun `장바구니 수량 수정 실패 - 재고 초과`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 5
            )
            val addResponse = cartService.addToCart(
                user.id.value,
                CartRequest(product.id.value, 1)
            )
            val cartId = addResponse.items.first().id

            // When & Then
            val updateRequest = UpdateCartRequest(quantity = 10)
            assertFailsWith<InsufficientStockException> {
                cartService.updateCartQuantity(cartId, user.id.value, updateRequest)
            }
        }
    }

    @Test
    fun `장바구니 수량 수정 실패 - 0개로 변경`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val addResponse = cartService.addToCart(
                user.id.value,
                CartRequest(product.id.value, 2)
            )
            val cartId = addResponse.items.first().id

            // When & Then
            val updateRequest = UpdateCartRequest(quantity = 0)
            assertFailsWith<InvalidInputException> {
                cartService.updateCartQuantity(cartId, user.id.value, updateRequest)
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
            assertEquals(0, BigDecimal(addResponse.totalPrice).compareTo(BigDecimal("20000")), "총 금액이 20000이어야 합니다") // 10000 * 2
            val updateRequest = UpdateCartRequest(quantity = 5)
            val response = cartService.updateCartQuantity(cartId, user.id.value, updateRequest)

            // Then
            assertEquals(0, BigDecimal(response.totalPrice).compareTo(BigDecimal("50000")), "총 금액이 50000이어야 합니다") // 10000 * 5
        }
    }

    @Test
    fun `장바구니 수량 수정 실패 - 상품 상태 변경으로 판매 중단`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val added = cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))
            val cartId = added.items.first().id

            query {
                productRepository.updateProductStatus(product.id.value, ProductStatus.DISCONTINUED)
            }

            assertFailsWith<ProductInactiveException> {
                cartService.updateCartQuantity(cartId, user.id.value, UpdateCartRequest(2))
            }
        }
    }

    @Test
    fun `장바구니 수량 수정 실패 - 상품 상태 변경으로 품절`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val added = cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))
            val cartId = added.items.first().id

            query {
                productRepository.updateProductStatus(product.id.value, ProductStatus.SOLD_OUT)
            }

            assertFailsWith<ProductSoldOutException> {
                cartService.updateCartQuantity(cartId, user.id.value, UpdateCartRequest(2))
            }
        }
    }

    @Test
    fun `장바구니 수량 수정 실패 - 멤버십 접근 불가`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                requiredTier = SubscriptionPlanTier.FREE
            )
            val added = cartService.addToCart(user.id.value, CartRequest(product.id.value, 1))
            val cartId = added.items.first().id

            query {
                productRepository.updateProduct(
                    productId = product.id.value,
                    name = null,
                    description = null,
                    price = null,
                    originalPrice = null,
                    stock = null,
                    categoryId = null,
                    brandName = null,
                    imageUrls = null,
                    detailContent = null,
                    tags = null,
                    status = null,
                    requiredTier = SubscriptionPlanTier.TIER2
                )
            }

            assertFailsWith<SubscriptionRequiredException> {
                cartService.updateCartQuantity(cartId, user.id.value, UpdateCartRequest(2))
            }
        }
    }

    @Test
    fun `장바구니 수량 수정 실패 - 자신의 상품 우회 데이터`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            // 우회 데이터: 본인 상품이 장바구니에 있다고 가정
            TestFixtures.addToCart(creator.id.value, product.id.value, 1)
            val cart = query { cartRepository.findCartItem(creator.id.value, product.id.value) }
            assertNotNull(cart)

            assertFailsWith<InvalidInputException> {
                cartService.updateCartQuantity(cart.id.value, creator.id.value, UpdateCartRequest(2))
            }
        }
    }

    // ===== 장바구니 삭제 테스트 (3개) =====

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
            assertNotNull(response)
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
            assertFailsWith<ForbiddenException> {
                cartService.removeFromCart(9999, user.id.value)
            }
        }
    }

    // ===== 장바구니 비우기 테스트 (2개) =====

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
    fun `장바구니 전체 비우기 - 이미 빈 장바구니`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When & Then
            // 빈 장바구니 비우기 - 예외가 발생하지 않아야 함
            cartService.clearCart(user.id.value)
        }
    }
}
