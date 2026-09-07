package com.ninezero.service

import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.features.commerce.data.CartRepositoryImpl
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.data.WishlistRepositoryImpl
import com.ninezero.features.commerce.domain.WishlistService
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WishlistService 테스트
 *
 * 테스트 케이스: 12개
 * - 찜하기 토글: 4개
 * - 찜하기 목록 조회: 3개
 * - 찜하기 상태 확인: 2개
 * - 장바구니 이동: 3개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WishlistServiceTest {

    private lateinit var wishlistService: WishlistService
    private lateinit var wishlistRepository: WishlistRepositoryImpl
    private lateinit var productRepository: ProductRepositoryImpl
    private lateinit var cartRepository: CartRepositoryImpl
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var planRepository: SubscriptionPlanRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            wishlistRepository = WishlistRepositoryImpl()
            productRepository = ProductRepositoryImpl()
            cartRepository = CartRepositoryImpl()
            subscriptionRepository = SubscriptionRepositoryImpl()
            planRepository = SubscriptionPlanRepositoryImpl()

            wishlistService = WishlistService(
                wishlistRepository = wishlistRepository,
                productRepository = productRepository,
                cartRepository = cartRepository,
                subscriptionRepository = subscriptionRepository,
                planRepository = planRepository
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

    // ===== 찜하기 토글 테스트 (4개) =====

    @Test
    fun `찜하기 추가 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            val response = wishlistService.toggleWishlist(user.id.value, product.id.value)

            assertNotNull(response)
            assertTrue(response.isWishlisted, "상품이 찜하기 목록에 추가되어야 합니다")
            assertTrue(response.message.contains("추가") || response.message.contains("ADDED"))
        }
    }

    @Test
    fun `찜하기 제거 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            // 찜하기 추가
            wishlistService.toggleWishlist(user.id.value, product.id.value)

            // 찜하기 제거
            val response = wishlistService.toggleWishlist(user.id.value, product.id.value)

            assertNotNull(response)
            assertTrue(!response.isWishlisted, "상품이 찜하기 목록에서 제거되어야 합니다")
            assertTrue(response.message.contains("제거") || response.message.contains("REMOVED"))
        }
    }

    @Test
    fun `찜하기 토글 - 여러 번 반복`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            // 추가
            val response1 = wishlistService.toggleWishlist(user.id.value, product.id.value)
            assertTrue(response1.isWishlisted)

            // 제거
            val response2 = wishlistService.toggleWishlist(user.id.value, product.id.value)
            assertTrue(!response2.isWishlisted)

            // 다시 추가
            val response3 = wishlistService.toggleWishlist(user.id.value, product.id.value)
            assertTrue(response3.isWishlisted)
        }
    }

    @Test
    fun `찜하기 카운트 확인`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            // 한 사용자가 여러 상품을 찜하기
            val user = TestFixtures.createTestUser()
            val products = TestFixtures.createTestProducts(creator.id.value, 3)

            products.forEach { product ->
                wishlistService.toggleWishlist(user.id.value, product.id.value)
            }

            // 4번째 상품 추가하면서 카운트 확인
            val product4 = TestFixtures.createTestProduct(creator.id.value)
            val response = wishlistService.toggleWishlist(user.id.value, product4.id.value)

            assertEquals(true, response.isWishlisted)
            assertEquals(4, response.wishlistCount, "사용자의 총 찜하기 개수가 4개여야 합니다")
        }
    }

    // ===== 찜하기 목록 조회 테스트 (3개) =====

    @Test
    fun `찜하기 목록 조회 성공 - 빈 목록`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val response = wishlistService.getWishlist(user.id.value, page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(0, response.items.size)
            assertEquals(0, response.totalCount)
        }
    }

    @Test
    fun `찜하기 목록 조회 성공 - 여러 상품`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val products = TestFixtures.createTestProducts(creator.id.value, 3)

            // 3개 상품 모두 찜하기
            products.forEach { product ->
                wishlistService.toggleWishlist(user.id.value, product.id.value)
            }

            val response = wishlistService.getWishlist(user.id.value, page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(3, response.items.size)
            assertEquals(3, response.totalCount)
        }
    }

    @Test
    fun `찜하기 목록 조회 - 페이지네이션`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val products = TestFixtures.createTestProducts(creator.id.value, 5)

            // 5개 상품 모두 찜하기
            products.forEach { product ->
                wishlistService.toggleWishlist(user.id.value, product.id.value)
            }

            // 첫 페이지 (2개)
            val page1 = wishlistService.getWishlist(user.id.value, page = 1, limit = 2)
            assertEquals(2, page1.items.size)
            assertEquals(5, page1.totalCount)

            // 두 번째 페이지 (2개)
            val page2 = wishlistService.getWishlist(user.id.value, page = 2, limit = 2)
            assertEquals(2, page2.items.size)

            // 세 번째 페이지 (1개)
            val page3 = wishlistService.getWishlist(user.id.value, page = 3, limit = 2)
            assertEquals(1, page3.items.size)
        }
    }

    // ===== 찜하기 상태 확인 테스트 (2개) =====

    @Test
    fun `찜하기 상태 확인 - 찜하지 않음`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            val status = wishlistService.checkWishlistStatus(user.id.value, product.id.value)

            assertNotNull(status)
            assertEquals(false, status["isWishlisted"])
        }
    }

    @Test
    fun `찜하기 상태 확인 - 찜함`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            // 찜하기 추가
            wishlistService.toggleWishlist(user.id.value, product.id.value)

            val status = wishlistService.checkWishlistStatus(user.id.value, product.id.value)

            assertNotNull(status)
            assertEquals(true, status["isWishlisted"])
        }
    }

    // ===== 장바구니 이동 테스트 (3개) =====

    @Test
    fun `찜하기에서 장바구니로 이동 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 100
            )

            // 찜하기 추가
            wishlistService.toggleWishlist(user.id.value, product.id.value)

            // 장바구니로 이동
            val message = wishlistService.moveToCart(user.id.value, product.id.value)

            assertNotNull(message)
            assertTrue(message.contains("장바구니") || message.contains("CART"))

            // 찜하기 목록에서 제거 확인
            val wishlistStatus = wishlistService.checkWishlistStatus(user.id.value, product.id.value)
            assertEquals(false, wishlistStatus["isWishlisted"])

            // 장바구니에 추가 확인
            val cartItem = query {
                cartRepository.findCartItem(user.id.value, product.id.value)
            }
            assertNotNull(cartItem, "장바구니에 상품이 추가되어야 합니다")
        }
    }

    @Test
    fun `장바구니 이동 실패 - 찜하기 목록에 없음`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            // 찜하기 하지 않고 바로 장바구니로 이동 시도
            assertFailsWith<NotFoundException> {
                wishlistService.moveToCart(user.id.value, product.id.value)
            }
        }
    }

    @Test
    fun `장바구니 이동 실패 - 재고 부족`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 0 // 재고 없음
            )

            // 찜하기 추가
            wishlistService.toggleWishlist(user.id.value, product.id.value)

            // 장바구니로 이동 시도 (재고 부족으로 실패)
            assertFailsWith<Exception> {
                wishlistService.moveToCart(user.id.value, product.id.value)
            }
        }
    }
}
