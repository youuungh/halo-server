package com.ninezero.service

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.common.util.encodeToJson
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.upload.MediaDraftDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.domain.ProductService
import com.ninezero.features.commerce.presentation.models.DetailContentBlock
import com.ninezero.features.commerce.presentation.models.request.ProductImageRef
import com.ninezero.features.commerce.presentation.models.request.ProductRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateProductRequest
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.tag.data.TagRepositoryImpl
import com.ninezero.features.upload.data.MediaDraftRepositoryImpl
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
                fileUploadService.deleteByUrl(any())
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
                mediaDraftService = MediaDraftService(
                    mediaDraftRepository = MediaDraftRepositoryImpl(),
                    mediaUploadService = mockk(relaxed = true),
                    fileUploadService = fileUploadService,
                    imageProcessingService = imageProcessingService,
                    videoProcessingService = mockk(relaxed = true),
                    coroutineScope = CoroutineScope(Dispatchers.Unconfined)
                ),
                fileUploadService = fileUploadService,
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

    // ===== 상품 생성 테스트 =====

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

            assertFailsWith<ForbiddenException> {
                productService.createProduct(user.id.value, request)
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

    @Test
    fun `상품 생성 실패 - 상세 이미지 URL 직접 지정이나 초안 없는 이미지 블록`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val withDetailImageUrl = productRequest(
                detailContent = listOf(DetailContentBlock(type = "image", url = "https://example.com/other.jpg"))
            )
            val withoutSource = productRequest(
                detailContent = listOf(DetailContentBlock(type = "image", url = ""))
            )

            assertFailsWith<InvalidInputException> {
                productService.createProduct(creator.id.value, withDetailImageUrl)
            }
            assertFailsWith<InvalidInputException> {
                productService.createProduct(creator.id.value, withoutSource)
            }
            assertEquals(0L, query { ProductDao.all().count() })
        }
    }

    @Test
    fun `상품 생성 - 대표·상세 초안을 순서대로 거두어 주소로 채우고 초안은 지운다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val (first, second) = List(2) { TestFixtures.createTestMediaDraft(creator.id.value, targetType = MediaDraftTargetType.PRODUCT) }
            val detail = TestFixtures.createTestMediaDraft(creator.id.value, targetType = MediaDraftTargetType.PRODUCT)

            val response = productService.createProduct(
                creator.id.value,
                productRequest(
                    imageDraftIds = listOf(second.id.value, first.id.value),
                    detailContent = listOf(
                        DetailContentBlock(type = "text", content = "설명"),
                        DetailContentBlock(type = "image", blockId = "b1", draftId = detail.id.value)
                    )
                )
            )

            assertEquals(listOf(second.url, first.url), response.imageUrls)
            val imageBlock = response.detailContent.single { it.type == "image" }
            assertEquals(detail.url, imageBlock.url)
            assertEquals(100, imageBlock.width)
            assertNull(imageBlock.draftId)
            assertEquals(0L, query { MediaDraftDao.all().count() })
        }
    }

    @Test
    fun `상품 생성 실패 - 남의 초안이나 글용 초안이면 상품이 생기지 않는다`() {
        runBlocking {
            val (creator, other) = listOf(TestFixtures.createTestCreator(), TestFixtures.createTestUser())
            val others = TestFixtures.createTestMediaDraft(other.id.value, targetType = MediaDraftTargetType.PRODUCT)
            val postDraft = TestFixtures.createTestMediaDraft(creator.id.value, targetType = MediaDraftTargetType.POST)

            assertFailsWith<NotFoundException> {
                productService.createProduct(creator.id.value, productRequest(imageDraftIds = listOf(others.id.value)))
            }
            assertFailsWith<NotFoundException> {
                productService.createProduct(
                    creator.id.value,
                    productRequest(detailContent = listOf(DetailContentBlock(type = "image", draftId = postDraft.id.value)))
                )
            }

            assertEquals(0L, query { ProductDao.all().count() })
            assertEquals(2L, query { MediaDraftDao.all().count() })
        }
    }

    @Test
    fun `상품 생성 실패 - 유료 등급의 활성 플랜 없음`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            val request = ProductRequest(
                name = "테스트 상품",
                description = "테스트 상품 설명",
                price = "10000",
                stock = 100,
                requiredTier = SubscriptionPlanTier.TIER1
            )

            assertFailsWith<SubscriptionPlanRequiredException> {
                productService.createProduct(creator.id.value, request)
            }
        }
    }

    @Test
    fun `상품 생성 성공 - ADMIN은 플랜 없이 유료 등급 상품 등록`() {
        runBlocking {
            val admin = TestFixtures.createTestAdmin()

            val request = ProductRequest(
                name = "테스트 상품",
                description = "테스트 상품 설명",
                price = "10000",
                stock = 100,
                requiredTier = SubscriptionPlanTier.TIER1
            )

            val response = productService.createProduct(admin.id.value, request)

            assertEquals(SubscriptionPlanTier.TIER1, response.requiredTier)
        }
    }

    // ===== 상품 조회 테스트 =====

    @Test
    fun `상품 조회 - 캐시 적중이어도 가격·재고는 현재 상품 행 기준`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value, stock = 10)
            // 캐시 생성 뒤 가격·재고가 바뀐 상황
            val fresh = productService.getProductById(product.id.value, null)
            assertEquals(product.price.toAmountString(), fresh.price)  // 금액은 ".00" 없는 표기
            val stale = fresh.copy(stock = 999, price = "1")
            query { ProductDao.findById(product.id.value)!!.stock = 7 }
            coEvery { cacheService.get<Any>(CacheKeys.product(product.id.value, null), any()) } returns stale

            try {
                val response = productService.getProductById(product.id.value, null)

                assertEquals(7, response.stock)
                assertEquals(product.price.toAmountString(), response.price)
                assertEquals(stale.creator, response.creator)
            } finally {
                coEvery { cacheService.get<Any>(any(), any()) } returns null
            }
        }
    }

    @Test
    fun `연관 상품 - 캐시 적중이어도 끝난 타임딜은 판매가로 돌아온다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            // 캐시 생성 때는 딜 중이었고 지금은 딜이 끝난 상황
            val stale = productService.getProductById(product.id.value, null).copy(
                price = "8000.00",
                discountRate = 47,
                dealPrice = "8000.00",
                dealStartAt = LocalDateTime(2020, 1, 1, 0, 0),
                dealEndAt = LocalDateTime(2020, 1, 2, 0, 0),
                isDealActive = true
            )
            coEvery { cacheService.get<Any>(CacheKeys.relatedProducts(product.id.value, null), any()) } returns listOf(stale)

            try {
                val response = productService.getRelatedProducts(product.id.value).single()

                assertEquals(false, response.isDealActive)
                assertEquals(product.price.toAmountString(), response.price)
                assertEquals(33, response.discountRate)
            } finally {
                coEvery { cacheService.get<Any>(any(), any()) } returns null
            }
        }
    }

    @Test
    fun `내 상품 목록 - 삭제한 상품은 목록에서 빠진다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            TestFixtures.createTestProducts(creator.id.value, 2)
            TestFixtures.createTestProduct(creator.id.value, name = "삭제한 상품", isActive = false)

            val response = productService.getMyProducts(creator.id.value, limit = 10)

            assertEquals(2, response.items.size)
            assertEquals(false, response.hasNext)
        }
    }

    @Test
    fun `크리에이터 스토어 - 삭제한 상품은 전체 상품 목록과 총수에서 빠진다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            TestFixtures.createTestProducts(creator.id.value, 2)
            TestFixtures.createTestProduct(creator.id.value, name = "삭제한 상품", isActive = false)

            val response = productService.getCreatorStoreSections(creator.id.value, currentUserId = null, page = 1, limit = 10)

            assertEquals(2, response.allProducts.items.size)
            assertEquals(2, response.allProducts.totalCount)
        }
    }

    // ===== 상품 수정 테스트 =====

    @Test
    fun `상품 수정 실패 - 0원 이하 가격`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)

            assertFailsWith<InvalidPriceException> {
                productService.updateProduct(product.id.value, creator.id.value, UpdateProductRequest(price = "-10000"))
            }
            assertFailsWith<InvalidPriceException> {
                productService.updateProduct(product.id.value, creator.id.value, UpdateProductRequest(price = "0"))
            }
        }
    }

    @Test
    fun `상품 수정 실패 - 저장된 정가보다 높은 판매가`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            // 정가 15000원 상품
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                price = BigDecimal("10000"),
                originalPrice = BigDecimal("15000")
            )

            assertFailsWith<InvalidPriceException> {
                productService.updateProduct(product.id.value, creator.id.value, UpdateProductRequest(price = "20000"))
            }
        }
    }

    @Test
    fun `상품 수정 실패 - 가격 상한 초과`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)

            assertFailsWith<InvalidPriceException> {
                productService.updateProduct(
                    product.id.value,
                    creator.id.value,
                    UpdateProductRequest(price = "100000000", originalPrice = "100000000")
                )
            }
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

            assertFailsWith<ForbiddenException> {
                productService.updateProduct(product.id.value, creator2.id.value, request)
            }
        }
    }

    @Test
    fun `상품 수정 실패 - 저장되지 않은 이미지 URL`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)

            assertFailsWith<InvalidInputException> {
                productService.updateProduct(
                    product.id.value, creator.id.value,
                    UpdateProductRequest(images = listOf(ProductImageRef(url = "https://example.com/other.jpg")))
                )
            }
            assertFailsWith<InvalidInputException> {
                productService.updateProduct(
                    product.id.value, creator.id.value,
                    UpdateProductRequest(detailContent = listOf(DetailContentBlock(type = "image", url = "https://example.com/other.jpg")))
                )
            }
        }
    }

    @Test
    fun `상품 수정 - 저장된 이미지는 유지하고 새 초안은 그 자리에 채우며 빠진 파일은 지운다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val kept = "https://cdn/p1.jpg"
            val dropped = "https://cdn/p2.jpg"
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value, imageUrls = listOf(kept, dropped).encodeToJson())
            val draft = TestFixtures.createTestMediaDraft(creator.id.value, targetType = MediaDraftTargetType.PRODUCT)
            val detailDraft = TestFixtures.createTestMediaDraft(creator.id.value, targetType = MediaDraftTargetType.PRODUCT)
            clearMocks(fileUploadService, answers = false)

            val response = productService.updateProduct(
                product.id.value, creator.id.value,
                UpdateProductRequest(
                    images = listOf(ProductImageRef(draftId = draft.id.value), ProductImageRef(url = kept)),
                    detailContent = listOf(DetailContentBlock(type = "image", blockId = "b1", draftId = detailDraft.id.value))
                )
            )

            assertEquals(listOf(draft.url, kept), response.imageUrls)
            assertEquals(detailDraft.url, response.detailContent.single().url)
            coVerify { fileUploadService.deleteByUrls(listOf(dropped)) }
            assertEquals(0L, query { MediaDraftDao.all().count() })
        }
    }

    @Test
    fun `상품 수정 실패 - 주소와 초안 ID를 둘 다 주거나 둘 다 비우면 거절`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creatorId = creator.id.value)

            assertFailsWith<InvalidInputException> {
                productService.updateProduct(product.id.value, creator.id.value, UpdateProductRequest(images = listOf(ProductImageRef())))
            }
            assertFailsWith<InvalidInputException> {
                productService.updateProduct(
                    product.id.value, creator.id.value,
                    UpdateProductRequest(detailContent = listOf(DetailContentBlock(type = "image", url = "https://cdn/x.jpg", draftId = 1)))
                )
            }
        }
    }

    @Test
    fun `상품 수정 - 정가를 지우면서 판매가를 옛 정가보다 올릴 수 있다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                price = BigDecimal("10000"),
                originalPrice = BigDecimal("15000")
            )

            val response = productService.updateProduct(
                product.id.value,
                creator.id.value,
                UpdateProductRequest(price = "20000", originalPrice = "")
            )

            assertEquals(0, response.price.toBigDecimal().compareTo(BigDecimal("20000")))
            assertNull(response.originalPrice)
        }
    }

    @Test
    fun `상품 수정 실패 - 이미지 한도 초과면 파일을 지우지 않고 거절`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val storedUrls = (0..Constants.Commerce.MAX_PRODUCT_IMAGES).map { "https://cdn/p$it.jpg" }
            val product = TestFixtures.createTestProduct(creator.id.value, imageUrls = storedUrls.encodeToJson())
            clearMocks(fileUploadService, answers = false)

            assertFailsWith<InvalidInputException> {
                productService.updateProduct(
                    product.id.value,
                    creator.id.value,
                    UpdateProductRequest(images = storedUrls.map { ProductImageRef(url = it) })
                )
            }

            coVerify(exactly = 0) { fileUploadService.deleteByUrls(any()) }
            val stored = query { productRepository.findProductById(product.id.value)!!.imageUrls.decodeJsonToList() }
            assertEquals(storedUrls, stored)
        }
    }

    @Test
    fun `상품 수정 실패 - 유지 이미지와 새 초안을 합쳐 한도를 넘으면 초안을 거두지 않는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val oldUrls = (0 until Constants.Commerce.MAX_PRODUCT_IMAGES - 1).map { "https://example.com/old$it.jpg" }
            val product = TestFixtures.createTestProduct(creator.id.value, imageUrls = oldUrls.encodeToJson())
            val drafts = List(2) { TestFixtures.createTestMediaDraft(creator.id.value, targetType = MediaDraftTargetType.PRODUCT) }

            assertFailsWith<InvalidInputException> {
                productService.updateProduct(
                    product.id.value, creator.id.value,
                    UpdateProductRequest(images = oldUrls.map { ProductImageRef(url = it) } + drafts.map { ProductImageRef(draftId = it.id.value) })
                )
            }

            assertEquals(2L, query { MediaDraftDao.all().count() })
            val stored = query { productRepository.findProductById(product.id.value)!!.imageUrls.decodeJsonToList() }
            assertEquals(oldUrls, stored)
        }
    }

    // ===== 상품 삭제 테스트 =====

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

    // ===== 재고 관리 테스트 =====

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

    // ===== 상품 상태 관리 테스트 =====

    @Test
    fun `상품 상태 토글 성공 - 판매 중단과 재개`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                status = ProductStatus.ACTIVE
            )

            val message = productService.toggleProductStatus(product.id.value, creator.id.value)

            assertEquals(Messages.Commerce.PRODUCT_DISCONTINUED, message)

            // 상태 확인
            val updated = query {
                productRepository.findProductById(product.id.value)
            }
            assertEquals(ProductStatus.DISCONTINUED, updated?.status)

            // 다시 토글하면 재개
            assertEquals(Messages.Commerce.PRODUCT_RESUMED, productService.toggleProductStatus(product.id.value, creator.id.value))
            assertEquals(ProductStatus.ACTIVE, query { productRepository.findProductById(product.id.value) }?.status)
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

    private fun productRequest(
        imageDraftIds: List<Int> = emptyList(),
        detailContent: List<DetailContentBlock>? = null
    ) = ProductRequest(
        name = "테스트 상품",
        description = "테스트 상품 설명",
        price = "10000",
        stock = 100,
        imageDraftIds = imageDraftIds,
        detailContent = detailContent
    )

    @Test
    fun `상품 수정 응답은 내 상품 목록처럼 섹션 태그를 담는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val tag = query { tagRepository.createTag(creator.id.value, "신상", TagTargetType.PRODUCT, isSectionEnabled = true) }

            val response = productService.updateProduct(
                product.id.value,
                creator.id.value,
                UpdateProductRequest(tagIds = listOf(tag.id.value), sectionTagId = tag.id.value)
            )

            assertEquals(tag.id.value, response.sectionTag?.id)
            assertEquals(response.sectionTag, productService.getMyProducts(creator.id.value).items.single().sectionTag)
        }
    }
}
