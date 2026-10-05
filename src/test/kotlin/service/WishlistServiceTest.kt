package com.ninezero.service

import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.ProductNotFoundException
import com.ninezero.core.common.exception.ProductSoldOutException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.commerce.ProductTable
import com.ninezero.features.commerce.data.CartRepositoryImpl
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.data.WishlistRepositoryImpl
import com.ninezero.features.commerce.domain.WishlistService
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import com.ninezero.core.database.entities.commerce.WishlistTable
import org.jetbrains.exposed.dao.EntityChangeType
import org.jetbrains.exposed.dao.EntityHook
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ===== 찜하기 토글 테스트 =====

    @Test
    fun `삭제된 상품도 찜하기에서 뺄 수 있다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            wishlistService.toggleWishlist(user.id.value, product.id.value)
            query { productRepository.deleteProduct(product.id.value) }  // 찜한 뒤 판매자가 상품 삭제

            val response = wishlistService.toggleWishlist(user.id.value, product.id.value)

            assertFalse(response.isWishlisted)
            assertEquals(0, wishlistService.getWishlist(user.id.value).items.size)
        }
    }

    @Test
    fun `없는 상품과 삭제된 상품은 찜하기에 담을 수 없다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            query { productRepository.deleteProduct(product.id.value) }

            assertFailsWith<ProductNotFoundException> {
                wishlistService.toggleWishlist(user.id.value, 999_999)
            }
            assertFailsWith<ProductNotFoundException> {
                wishlistService.toggleWishlist(user.id.value, product.id.value)
            }
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

    // ===== 장바구니 이동 테스트 =====

    @Test
    fun `장바구니 이동 - 찜 수가 이미 0이면 음수가 되지 않는다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value, stock = 100)
            wishlistService.toggleWishlist(user.id.value, product.id.value)
            query { ProductTable.update({ ProductTable.id eq product.id }) { it[likeCount] = 0 } }  // 집계가 어긋난 상태

            wishlistService.moveToCart(user.id.value, product.id.value)

            val likeCount = query { productRepository.findProductById(product.id.value)!!.likeCount }
            assertEquals(0, likeCount)
            // 찜은 풀리고 장바구니에 담김
            assertEquals(false, wishlistService.checkWishlistStatus(user.id.value, product.id.value).isWishlisted)
            assertNotNull(query { cartRepository.findCartItem(user.id.value, product.id.value) })
        }
    }

    @Test
    fun `장바구니 이동 실패 - 담긴 수량과 합쳐 최대 수량 초과`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value, stock = 200)
            TestFixtures.addToCart(user.id.value, product.id.value, quantity = 99)
            wishlistService.toggleWishlist(user.id.value, product.id.value)

            assertFailsWith<InvalidInputException> { wishlistService.moveToCart(user.id.value, product.id.value) }
            val quantity = query { cartRepository.findCartItem(user.id.value, product.id.value)!!.quantity }
            assertEquals(99, quantity)
        }
    }

    @Test
    fun `장바구니 이동 실패 - 자신의 상품`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)
            wishlistService.toggleWishlist(creator.id.value, product.id.value)

            assertFailsWith<InvalidInputException> { wishlistService.moveToCart(creator.id.value, product.id.value) }
            assertNull(query { cartRepository.findCartItem(creator.id.value, product.id.value) })
        }
    }

    @Test
    fun `장바구니 이동 실패 - 품절`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 0
            )

            // 찜하기 추가
            wishlistService.toggleWishlist(user.id.value, product.id.value)

            assertFailsWith<ProductSoldOutException> {
                wishlistService.moveToCart(user.id.value, product.id.value)
            }
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `두 사람이 거의 동시에 찜해도 찜 수가 사라지지 않는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val first = TestFixtures.createTestUser(email = "wish-a@example.com", username = "wisha")
            val second = TestFixtures.createTestUser(email = "wish-b@example.com", username = "wishb")

            // 첫 찜 행이 저장된 직후 다른 사람의 찜이 끼어듦
            val interleaved = AtomicBoolean(false)
            val hook = EntityHook.subscribe { change ->
                if (change.changeType == EntityChangeType.Created && change.entityClass.table == WishlistTable &&
                    interleaved.compareAndSet(false, true)
                ) {
                    thread { runBlocking { wishlistService.toggleWishlist(second.id.value, product.id.value) } }.join(1000)
                }
            }

            try { wishlistService.toggleWishlist(first.id.value, product.id.value) } finally { EntityHook.unsubscribe(hook) }

            assertTrue(interleaved.get())
            assertEquals(2, query { productRepository.findProductById(product.id.value)!!.likeCount })
        }
    }
}
