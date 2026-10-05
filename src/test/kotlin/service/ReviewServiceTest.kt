package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.commerce.OrderDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.features.commerce.data.OrderRepositoryImpl
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.data.ReviewRepositoryImpl
import com.ninezero.features.commerce.domain.ProductService
import com.ninezero.features.commerce.domain.ReviewService
import com.ninezero.features.commerce.presentation.models.request.ReviewRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateReviewRequest
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import io.mockk.clearMocks
import io.mockk.coVerify
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewServiceTest {

    private lateinit var reviewService: ReviewService
    private lateinit var reviewRepository: ReviewRepositoryImpl
    private lateinit var orderRepository: OrderRepositoryImpl
    private lateinit var productRepository: ProductRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var notificationService: NotificationService
    private lateinit var fileUploadService: FileUploadService
    private lateinit var imageProcessingService: ImageProcessingService
    private lateinit var cacheService: CacheService
    private lateinit var productService: ProductService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            reviewRepository = ReviewRepositoryImpl()
            orderRepository = OrderRepositoryImpl()
            productRepository = ProductRepositoryImpl()
            userRepository = UserRepositoryImpl()

            // Mock 객체 생성
            notificationService = mockk(relaxed = true)
            fileUploadService = mockk(relaxed = true)
            imageProcessingService = mockk(relaxed = true)
            cacheService = mockk(relaxed = true)
            productService = mockk(relaxed = true)

            // Mock 동작 정의
            coEvery {
                notificationService.sendProductReviewedNotification(any(), any(), any(), any())
            } returns mockk()

            coEvery {
                imageProcessingService.validateImage(any(), any())
            } returns true

            coEvery {
                fileUploadService.uploadReviewImages(any(), any())
            } returns listOf("https://example.com/review-image.jpg")

            coEvery {
                fileUploadService.deleteByUrl(any())
            } returns true

            // CacheService mock 설정
            coEvery { cacheService.get<Any>(any(), any()) } returns null

            reviewService = ReviewService(
                reviewRepository = reviewRepository,
                orderRepository = orderRepository,
                productRepository = productRepository,
                userRepository = userRepository,
                notificationService = notificationService,
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
                cacheService = cacheService,
                productService = productService,
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

    /** 주문 status를 배송완료로 교체 */
    private suspend fun markDelivered(orderId: Int) {
        query {
            OrderDao.findById(orderId)!!.status = OrderStatus.DELIVERED
        }
    }

    // ===== 리뷰 작성 테스트 =====

    @Test
    fun `리뷰 작성 실패 - 본인 주문이 아님`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user1.id.value,
                productId = product.id.value
            )

            markDelivered(order.id.value)

            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 5,
                content = "좋습니다"
            )

            assertFailsWith<ForbiddenException> {
                reviewService.createReview(user2.id.value, request)
            }
        }
    }

    @Test
    fun `리뷰 작성 실패 - 배송 완료되지 않은 주문`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            // 주문 상태가 PENDING인 상태

            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 5,
                content = "좋습니다"
            )

            assertFailsWith<InvalidInputException> {
                reviewService.createReview(user.id.value, request)
            }
        }
    }

    @Test
    fun `리뷰 작성 실패 - 잘못된 별점 (0·범위 초과)`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            markDelivered(order.id.value)

            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 6,  // 잘못된 별점 (최대 5)
                content = "좋습니다"
            )

            assertFailsWith<InvalidInputException> {
                reviewService.createReview(user.id.value, request)
            }
            assertFailsWith<InvalidInputException> {
                reviewService.createReview(user.id.value, request.copy(rating = 0))  // 최소 1
            }
        }
    }

    @Test
    fun `리뷰 작성 실패 - 중복 리뷰`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            markDelivered(order.id.value)

            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 5,
                content = "좋습니다"
            )

            // 첫 번째 리뷰 작성 성공
            reviewService.createReview(user.id.value, request)

            // 두 번째 리뷰 작성 시도 - 중복 리뷰
            assertFailsWith<ConflictException> {
                reviewService.createReview(user.id.value, request)
            }
        }
    }

    // ===== 리뷰 조회 테스트 =====

    @Test
    fun `상품별 리뷰 목록 조회 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            markDelivered(order.id.value)

            // 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 5,
                content = "좋습니다"
            )
            reviewService.createReview(user.id.value, request)

            // 리뷰 목록 조회
            val response = reviewService.getReviewsByProduct(product.id.value, limit = 10)

            assertEquals(listOf(5), response.items.map { it.rating })
        }
    }

    @Test
    fun `내가 작성한 리뷰 목록 조회 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            markDelivered(order.id.value)

            // 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 4,
                content = "만족합니다"
            )
            reviewService.createReview(user.id.value, request)

            // 내 리뷰 목록 조회
            val response = reviewService.getUserReviews(user.id.value, user.id.value, limit = 10)

            assertEquals(1, response.items.size)
        }
    }

    @Test
    fun `리뷰 상세 조회 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            markDelivered(order.id.value)

            // 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 5,
                content = "좋은 상품입니다"
            )
            val created = reviewService.createReview(user.id.value, request)

            // 리뷰 상세 조회
            val response = reviewService.getReviewById(created.id)

            assertEquals(5, response.rating)
            assertEquals("좋은 상품입니다", response.content)
        }
    }

    // ===== 리뷰 수정 테스트 =====

    @Test
    fun `리뷰 수정+이미지 - 남길 것과 새 파일을 한 번에 반영`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(userId = user.id.value, productId = product.id.value)
            markDelivered(order.id.value)

            val created = reviewService.createReview(
                user.id.value,
                ReviewRequest(orderId = order.id.value, productId = product.id.value, rating = 4, content = "원래 내용")
            )
            // 기존 이미지 2장을 심어둔다
            query {
                reviewRepository.updateReview(
                    reviewId = created.id,
                    rating = null,
                    content = null,
                    images = listOf("https://cdn/old1.jpg", "https://cdn/old2.jpg")
                )
            }

            coEvery {
                fileUploadService.uploadReviewImages(any(), any())
            } returns listOf("https://cdn/new1.jpg")

            // When - old1만 남기고 새 파일 1장 추가
            val updated = reviewService.updateReviewWithImages(
                reviewId = created.id,
                userId = user.id.value,
                rating = 5,
                content = "수정된 내용입니다",
                keepImages = listOf("https://cdn/old1.jpg"),
                newFiles = listOf(ByteArray(10)),
                newContentTypes = listOf("image/jpeg")
            )

            // Then - 남긴 것 + 새 것 순서로 한 번에 저장, 본문·별점도 함께 반영
            assertEquals(listOf("https://cdn/old1.jpg", "https://cdn/new1.jpg"), updated.images)
            assertEquals(5, updated.rating)
            assertEquals("수정된 내용입니다", updated.content)
        }
    }

    @Test
    fun `리뷰 수정+이미지 - 이미지 파트가 없으면 기존 목록을 건드리지 않는다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(userId = user.id.value, productId = product.id.value)
            markDelivered(order.id.value)

            val created = reviewService.createReview(
                user.id.value,
                ReviewRequest(orderId = order.id.value, productId = product.id.value, rating = 3, content = "원래 내용")
            )
            query {
                reviewRepository.updateReview(
                    reviewId = created.id,
                    rating = null,
                    content = null,
                    images = listOf("https://cdn/keep.jpg")
                )
            }

            // When - 본문만 수정(keepImages=null, 새 파일 없음)
            val updated = reviewService.updateReviewWithImages(
                reviewId = created.id,
                userId = user.id.value,
                rating = null,
                content = "본문만 수정",
                keepImages = null,
                newFiles = null,
                newContentTypes = null
            )

            // Then - 이미지는 그대로
            assertEquals(listOf("https://cdn/keep.jpg"), updated.images)
            assertEquals("본문만 수정", updated.content)
        }
    }

    @Test
    fun `리뷰 수정+이미지 - 한도 초과면 업로드하지 않고 거부`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(userId = user.id.value, productId = product.id.value)
            markDelivered(order.id.value)

            val created = reviewService.createReview(
                user.id.value,
                ReviewRequest(orderId = order.id.value, productId = product.id.value, rating = 4, content = "원래 내용")
            )

            val max = com.ninezero.core.common.config.Constants.Commerce.MAX_REVIEW_IMAGES

            // When/Then - 남길 것 max개 + 새 파일 1개 = 초과
            assertFailsWith<InvalidInputException> {
                reviewService.updateReviewWithImages(
                    reviewId = created.id,
                    userId = user.id.value,
                    rating = null,
                    content = null,
                    keepImages = (1..max).map { "https://cdn/k$it.jpg" },
                    newFiles = listOf(ByteArray(10)),
                    newContentTypes = listOf("image/jpeg")
                )
            }
        }
    }

    @Test
    fun `리뷰 수정 실패 - 권한 없음 (다른 사용자)`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user1.id.value,
                productId = product.id.value
            )

            markDelivered(order.id.value)

            // user1이 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 4,
                content = "좋습니다"
            )
            val created = reviewService.createReview(user1.id.value, request)

            // user2가 수정 시도
            val updateRequest = UpdateReviewRequest(
                content = "수정된 내용"
            )

            assertFailsWith<ForbiddenException> {
                reviewService.updateReview(created.id, user2.id.value, updateRequest)
            }
        }
    }

    @Test
    fun `리뷰 수정+이미지 실패 - 남길 목록에 저장되지 않은 URL`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(userId = user.id.value, productId = product.id.value)
            markDelivered(order.id.value)

            val created = reviewService.createReview(
                user.id.value,
                ReviewRequest(orderId = order.id.value, productId = product.id.value, rating = 4, content = "원래 내용")
            )

            assertFailsWith<InvalidInputException> {
                reviewService.updateReviewWithImages(
                    reviewId = created.id,
                    userId = user.id.value,
                    rating = null,
                    content = null,
                    keepImages = listOf("https://cdn/others.jpg"),
                    newFiles = null,
                    newContentTypes = null
                )
            }
        }
    }

    // ===== 리뷰 삭제 테스트 =====

    @Test
    fun `리뷰 삭제 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            markDelivered(order.id.value)

            // 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 5,
                content = "좋습니다"
            )
            val created = reviewService.createReview(user.id.value, request)

            // 리뷰 삭제
            reviewService.deleteReview(created.id, user.id.value)

            // 삭제 확인
            assertFailsWith<NotFoundException> {
                reviewService.getReviewById(created.id)
            }
        }
    }

    // ===== 별점 관리 테스트 =====

    @Test
    fun `별점 분포 조회`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            // 다양한 별점의 리뷰 작성
            val ratings = listOf(5, 5, 4, 4, 3)
            ratings.forEachIndexed { index, rating ->
                val user = TestFixtures.createTestUser(
                    email = "user${index}@example.com",
                    username = "user$index"
                )
                val order = TestFixtures.createTestOrder(
                    userId = user.id.value,
                    productId = product.id.value
                )
                markDelivered(order.id.value)

                val request = ReviewRequest(
                    productId = product.id.value,
                    orderId = order.id.value,
                    rating = rating,
                    content = "리뷰 $rating"
                )
                reviewService.createReview(user.id.value, request)
            }

            val summary = reviewService.getReviewSummary(product.id.value)

            assertEquals(5, summary.totalReviews)
            assertEquals(mapOf(1 to 0, 2 to 0, 3 to 1, 4 to 2, 5 to 2), summary.ratingDistribution)
        }
    }

    @Test
    fun `사진 리뷰 업로드가 실패하면 판매자에게 알리지 않고 리뷰도 남기지 않는다`() {
        runBlocking {
            clearMocks(notificationService, answers = false)
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(userId = user.id.value, productId = product.id.value)
            markDelivered(order.id.value)
            coEvery { fileUploadService.uploadReviewImages(any(), any()) } throws IllegalStateException("storage down")

            try {
                assertFailsWith<IllegalStateException> {
                    reviewService.createReviewWithImages(
                        userId = user.id.value,
                        productId = product.id.value,
                        orderId = order.id.value,
                        rating = 5,
                        content = "사진 리뷰",
                        files = listOf(ByteArray(10)),
                        contentTypes = listOf("image/jpeg")
                    )
                }
            } finally {
                coEvery { fileUploadService.uploadReviewImages(any(), any()) } returns listOf("https://example.com/review-image.jpg")
            }

            coVerify(exactly = 0) { notificationService.sendProductReviewedNotification(any(), any(), any(), any()) }
            assertEquals(null, query { reviewRepository.findReviewByOrderProduct(order.id.value, product.id.value) })
        }
    }
}
