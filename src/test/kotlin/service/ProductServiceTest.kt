package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.domain.ProductService
import com.ninezero.features.commerce.presentation.models.request.ProductRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateProductRequest
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.tag.data.TagRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ProductService 테스트
 *
 * 테스트 케이스: 23개
 * - 상품 생성: 6개
 * - 상품 조회: 4개
 * - 상품 수정: 4개
 * - 상품 삭제: 2개
 * - 재고 관리: 3개
 * - 상품 상태 관리: 4개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductServiceTest {

    private lateinit var productService: ProductService
    private lateinit var productRepository: ProductRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var followRepository: FollowRepositoryImpl
    private lateinit var tagRepository: TagRepositoryImpl
    private lateinit var subscriptionRepository: SubscriptionRepositoryImpl
    private lateinit var planRepository: SubscriptionPlanRepositoryImpl
    private lateinit var notificationService: NotificationService
    private lateinit var fileUploadService: FileUploadService
    private lateinit var imageProcessingService: ImageProcessingService
    private lateinit var cacheService: CacheService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            productRepository = ProductRepositoryImpl()
            userRepository = UserRepositoryImpl()
            followRepository = FollowRepositoryImpl()
            tagRepository = TagRepositoryImpl()
            subscriptionRepository = SubscriptionRepositoryImpl()
            planRepository = SubscriptionPlanRepositoryImpl()

            // Mock 객체 생성
            notificationService = mockk(relaxed = true)
            fileUploadService = mockk(relaxed = true)
            imageProcessingService = mockk(relaxed = true)
            cacheService = mockk(relaxed = true)

            // Mock 동작 정의
            coEvery {
                imageProcessingService.validateImage(any(), any())
            } returns true

            coEvery {
                imageProcessingService.getFileExtension(any())
            } returns "jpg"

            coEvery {
                fileUploadService.uploadProductImages(any(), any(), any())
            } returns listOf("https://example.com/image1.jpg")

            coEvery {
                fileUploadService.deleteFileIfSupabase(any())
            } returns true

            // CacheService mock 설정
            coEvery { cacheService.get<Any>(any(), any()) } returns null

            productService = ProductService(
                productRepository = productRepository,
                userRepository = userRepository,
                followRepository = followRepository,
                tagRepository = tagRepository,
                subscriptionRepository = subscriptionRepository,
                planRepository = planRepository,
                notificationService = notificationService,
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
                cacheService = cacheService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined)
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

    // ===== 상품 생성 테스트 (6개) =====

    @Test
    fun `상품 생성 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = ProductRequest(
                name = "테스트 상품",
                description = "테스트 상품 설명",
                price = "10000",
                originalPrice = "15000",
                stock = 100,
                categoryId = null,
                brandName = "테스트 브랜드",
                imageUrls = listOf("https://example.com/image.jpg"),
                tags = listOf("태그1", "태그2"),
                tagIds = null,
                sectionTagId = null,
                requiredTier = SubscriptionPlanTier.FREE
            )

            val response = productService.createProduct(creator.id.value, request)

            assertNotNull(response)
            assertEquals("테스트 상품", response.name)
            assertEquals(0, response.price.toBigDecimal().compareTo(BigDecimal("10000")))
            assertEquals(100, response.stock)
            assertEquals(ProductStatus.ACTIVE, response.status)
        }
    }

    @Test
    fun `상품 생성 실패 - 권한 없음 (일반 사용자)`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            val request = ProductRequest(
                name = "테스트 상품",
                description = "테스트 상품 설명",
                price = "10000",
                stock = 100
            )

            assertFailsWith<PermissionDeniedException> {
                productService.createProduct(user.id.value, request)
            }
        }
    }

    @Test
    fun `상품 생성 실패 - 잘못된 가격 (음수)`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = ProductRequest(
                name = "테스트 상품",
                description = "테스트 상품 설명",
                price = "-1000",
                stock = 100
            )

            assertFailsWith<InvalidPriceException> {
                productService.createProduct(creator.id.value, request)
            }
        }
    }

    @Test
    fun `상품 생성 실패 - 잘못된 재고 (음수)`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = ProductRequest(
                name = "테스트 상품",
                description = "테스트 상품 설명",
                price = "10000",
                stock = -10
            )

            assertFailsWith<InvalidStockException> {
                productService.createProduct(creator.id.value, request)
            }
        }
    }

    @Test
    fun `상품 생성 실패 - 할인가격이 원가보다 높음`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = ProductRequest(
                name = "테스트 상품",
                description = "테스트 상품 설명",
                price = "20000",
                originalPrice = "15000",
                stock = 100
            )

            assertFailsWith<InvalidPriceException> {
                productService.createProduct(creator.id.value, request)
            }
        }
    }

    @Test
    fun `상품 생성 실패 - 상품명이 너무 짧음`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = ProductRequest(
                name = "짧",
                description = "테스트 상품 설명",
                price = "10000",
                stock = 100
            )

            assertFailsWith<InvalidProductNameException> {
                productService.createProduct(creator.id.value, request)
            }
        }
    }

    // ===== 상품 조회 테스트 (4개) =====

    @Test
    fun `상품 조회 성공 - 단일 상품`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            val response = productService.getProductById(product.id.value, creator.id.value)

            assertNotNull(response)
            assertEquals(product.name, response.name)
            // 금액 직렬화는 ".00" 스트립 컨벤션(toAmountString) — raw toString은 H2 decimal 스케일이 붙는다
            assertEquals(product.price.toAmountString(), response.price)
        }
    }

    @Test
    fun `상품 조회 실패 - 존재하지 않는 상품`() {
        runBlocking {
            assertFailsWith<ProductNotFoundException> {
                productService.getProductById(99999, null)
            }
        }
    }

    @Test
    fun `내 상품 목록 조회 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            TestFixtures.createTestProducts(creator.id.value, 3)

            val response = productService.getMyProducts(creator.id.value, page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(3, response.items.size)
            assertEquals(3, response.totalCount)
        }
    }

    @Test
    fun `내 상품 목록 조회 실패 - 권한 없음`() {
        runBlocking {
            val user = TestFixtures.createTestUser(role = UserRole.USER)

            assertFailsWith<PermissionDeniedException> {
                productService.getMyProducts(user.id.value, page = 1, limit = 10)
            }
        }
    }

    // ===== 상품 수정 테스트 (4개) =====

    @Test
    fun `상품 수정 성공 - 이름만 변경`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                name = "원래 상품명"
            )

            val request = UpdateProductRequest(
                name = "변경된 상품명"
            )

            val response = productService.updateProduct(product.id.value, creator.id.value, request)

            assertNotNull(response)
            assertEquals("변경된 상품명", response.name)
        }
    }

    @Test
    fun `상품 수정 성공 - 가격과 재고 변경`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                price = BigDecimal("10000"),
                stock = 100
            )

            val request = UpdateProductRequest(
                price = "20000",
                stock = 50
            )

            val response = productService.updateProduct(product.id.value, creator.id.value, request)

            assertNotNull(response)
            assertEquals(0, response.price.toBigDecimal().compareTo(BigDecimal("20000")))
            assertEquals(50, response.stock)
        }
    }

    @Test
    fun `상품 수정 실패 - 권한 없음 (다른 크리에이터)`() {
        runBlocking {
            val creator1 = TestFixtures.createTestCreator(email = "creator1@example.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@example.com", username = "creator2")
            val product = TestFixtures.createTestProduct(creatorId = creator1.id.value)

            val request = UpdateProductRequest(
                name = "변경된 상품명"
            )

            assertFailsWith<PermissionDeniedException> {
                productService.updateProduct(product.id.value, creator2.id.value, request)
            }
        }
    }

    @Test
    fun `상품 수정 실패 - 존재하지 않는 상품`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = UpdateProductRequest(
                name = "변경된 상품명"
            )

            assertFailsWith<ProductNotFoundException> {
                productService.updateProduct(99999, creator.id.value, request)
            }
        }
    }

    // ===== 상품 삭제 테스트 (2개) =====

    @Test
    fun `상품 삭제 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)

            productService.deleteProduct(product.id.value, creator.id.value)

            // 삭제 후 조회 시 실패 — findProductById가 soft-delete를 제외하므로 404(존재 은닉)
            assertFailsWith<ProductNotFoundException> {
                productService.getProductById(product.id.value, creator.id.value)
            }
        }
    }

    @Test
    fun `상품 삭제 실패 - 권한 없음`() {
        runBlocking {
            val creator1 = TestFixtures.createTestCreator(email = "creator1@example.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@example.com", username = "creator2")
            val product = TestFixtures.createTestProduct(creatorId = creator1.id.value)

            assertFailsWith<PermissionDeniedException> {
                productService.deleteProduct(product.id.value, creator2.id.value)
            }
        }
    }

    // ===== 재고 관리 테스트 (3개) =====

    @Test
    fun `재고 감소 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 100
            )

            val request = UpdateProductRequest(
                stock = 50
            )

            val response = productService.updateProduct(product.id.value, creator.id.value, request)

            assertNotNull(response)
            assertEquals(50, response.stock)
        }
    }

    @Test
    fun `재고 0으로 변경 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 100
            )

            val request = UpdateProductRequest(
                stock = 0
            )

            val response = productService.updateProduct(product.id.value, creator.id.value, request)

            assertNotNull(response)
            assertEquals(0, response.stock)
        }
    }

    @Test
    fun `재고 변경 실패 - 음수 재고`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                stock = 100
            )

            val request = UpdateProductRequest(
                stock = -10
            )

            assertFailsWith<InvalidStockException> {
                productService.updateProduct(product.id.value, creator.id.value, request)
            }
        }
    }

    // ===== 상품 상태 관리 테스트 (4개) =====

    @Test
    fun `상품 상태 토글 성공 - ACTIVE to DISCONTINUED`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                status = ProductStatus.ACTIVE
            )

            val message = productService.toggleProductStatus(product.id.value, creator.id.value)

            assertNotNull(message)
            assertTrue(message.contains("판매") || message.contains("종료"))

            // 상태 확인
            val updated = query {
                productRepository.findProductById(product.id.value)
            }
            assertEquals(ProductStatus.DISCONTINUED, updated?.status)
        }
    }

    @Test
    fun `상품 상태 토글 성공 - DISCONTINUED to ACTIVE`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                status = ProductStatus.DISCONTINUED
            )

            val message = productService.toggleProductStatus(product.id.value, creator.id.value)

            assertNotNull(message)
            assertTrue(message.contains("재개") || message.contains("판매"))

            // 상태 확인
            val updated = query {
                productRepository.findProductById(product.id.value)
            }
            assertEquals(ProductStatus.ACTIVE, updated?.status)
        }
    }

    @Test
    fun `상품 상태 토글 실패 - SOLD_OUT 상태는 토글 불가`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                status = ProductStatus.SOLD_OUT
            )

            assertFailsWith<ProductSoldOutException> {
                productService.toggleProductStatus(product.id.value, creator.id.value)
            }
        }
    }

    @Test
    fun `상품 상태 토글 실패 - 권한 없음`() {
        runBlocking {
            val creator1 = TestFixtures.createTestCreator(email = "creator1@example.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@example.com", username = "creator2")
            val product = TestFixtures.createTestProduct(creatorId = creator1.id.value)

            assertFailsWith<PermissionDeniedException> {
                productService.toggleProductStatus(product.id.value, creator2.id.value)
            }
        }
    }
}