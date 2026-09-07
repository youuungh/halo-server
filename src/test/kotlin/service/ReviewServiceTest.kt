package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
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
import io.ktor.server.plugins.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ReviewService 테스트
 *
 * 테스트 케이스: 18개
 * - 리뷰 작성: 6개
 * - 리뷰 조회: 3개
 * - 리뷰 수정: 3개
 * - 리뷰 삭제: 2개
 * - 별점 관리: 4개
 */
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
                imageProcessingService.getFileExtension(any())
            } returns "jpg"

            coEvery {
                fileUploadService.uploadReviewImages(any(), any(), any())
            } returns listOf("https://example.com/review-image.jpg")

            coEvery {
                fileUploadService.deleteFileIfSupabase(any())
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

    // ===== 리뷰 작성 테스트 (6개) =====

    @Test
    fun `리뷰 작성 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            // 주문 상태를 DELIVERED로 변경
            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 5,
                content = "매우 만족스러운 상품입니다!",
                images = listOf("https://example.com/review1.jpg")
            )

            val response = reviewService.createReview(user.id.value, request)

            assertNotNull(response)
            assertEquals(5, response.rating)
            assertEquals("매우 만족스러운 상품입니다!", response.content)
        }
    }

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

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

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

            assertFailsWith<BadRequestException> {
                reviewService.createReview(user.id.value, request)
            }
        }
    }

    @Test
    fun `리뷰 작성 실패 - 잘못된 별점 (범위 초과)`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 6, // 잘못된 별점 (최대 5)
                content = "좋습니다"
            )

            assertFailsWith<BadRequestException> {
                reviewService.createReview(user.id.value, request)
            }
        }
    }

    @Test
    fun `리뷰 작성 실패 - 잘못된 별점 (0)`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 0, // 잘못된 별점 (최소 1)
                content = "별로입니다"
            )

            assertFailsWith<BadRequestException> {
                reviewService.createReview(user.id.value, request)
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

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

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

    // ===== 리뷰 조회 테스트 (3개) =====

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

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

            // 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 5,
                content = "좋습니다"
            )
            reviewService.createReview(user.id.value, request)

            // 리뷰 목록 조회
            val response = reviewService.getReviewsByProduct(product.id.value, page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(1, response.reviews.items.size)
            assertTrue(response.avgRating > 0.0)
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

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

            // 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 4,
                content = "만족합니다"
            )
            reviewService.createReview(user.id.value, request)

            // 내 리뷰 목록 조회
            val response = reviewService.getMyReviews(user.id.value, user.id.value, page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(1, response.reviews.items.size)
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

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

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

            assertNotNull(response)
            assertEquals(5, response.rating)
            assertEquals("좋은 상품입니다", response.content)
        }
    }

    // ===== 리뷰 수정 테스트 (3개) =====

    @Test
    fun `리뷰 수정 성공 - 내용만 변경`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

            // 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 4,
                content = "원래 내용"
            )
            val created = reviewService.createReview(user.id.value, request)

            // 리뷰 수정
            val updateRequest = UpdateReviewRequest(
                content = "수정된 내용입니다"
            )

            val updated = reviewService.updateReview(created.id, user.id.value, updateRequest)

            assertNotNull(updated)
            assertEquals("수정된 내용입니다", updated.content)
            assertEquals(4, updated.rating) // 별점은 유지
        }
    }

    @Test
    fun `리뷰 수정 성공 - 별점과 내용 모두 변경`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

            // 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 3,
                content = "보통입니다"
            )
            val created = reviewService.createReview(user.id.value, request)

            // 리뷰 수정
            val updateRequest = UpdateReviewRequest(
                rating = 5,
                content = "재평가 후 매우 만족합니다"
            )

            val updated = reviewService.updateReview(created.id, user.id.value, updateRequest)

            assertNotNull(updated)
            assertEquals(5, updated.rating)
            assertEquals("재평가 후 매우 만족합니다", updated.content)
        }
    }

    @Test
    fun `리뷰 수정+이미지 - 남길 것과 새 파일을 한 번에 반영`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(userId = user.id.value, productId = product.id.value)
            query { orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED) }

            val created = reviewService.createReview(
                user.id.value,
                ReviewRequest(orderId = order.id.value, productId = product.id.value, rating = 4, content = "원래 내용")
            )
            // 기존 이미지 2장을 심어둔다
            reviewService.updateReview(
                created.id, user.id.value,
                UpdateReviewRequest(images = listOf("https://cdn/old1.jpg", "https://cdn/old2.jpg"))
            )

            coEvery {
                fileUploadService.uploadReviewImages(any(), any(), any())
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
            query { orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED) }

            val created = reviewService.createReview(
                user.id.value,
                ReviewRequest(orderId = order.id.value, productId = product.id.value, rating = 3, content = "원래 내용")
            )
            reviewService.updateReview(
                created.id, user.id.value,
                UpdateReviewRequest(images = listOf("https://cdn/keep.jpg"))
            )

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
            query { orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED) }

            val created = reviewService.createReview(
                user.id.value,
                ReviewRequest(orderId = order.id.value, productId = product.id.value, rating = 4, content = "원래 내용")
            )

            val max = com.ninezero.core.common.config.Constants.Commerce.MAX_REVIEW_IMAGES

            // When/Then - 남길 것 max개 + 새 파일 1개 = 초과
            assertFailsWith<BadRequestException> {
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

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

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

    // ===== 리뷰 삭제 테스트 (2개) =====

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

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

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

    @Test
    fun `리뷰 삭제 실패 - 권한 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user1.id.value,
                productId = product.id.value
            )

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

            // user1이 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 5,
                content = "좋습니다"
            )
            val created = reviewService.createReview(user1.id.value, request)

            // user2가 삭제 시도
            assertFailsWith<ForbiddenException> {
                reviewService.deleteReview(created.id, user2.id.value)
            }
        }
    }

    // ===== 별점 관리 테스트 (4개) =====

    @Test
    fun `리뷰 요약 정보 조회 - 리뷰 없음`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            val summary = reviewService.getReviewSummary(product.id.value)

            assertNotNull(summary)
            assertEquals(0, summary.totalReviews)
            assertEquals(0.0, summary.avgRating)
        }
    }

    @Test
    fun `리뷰 요약 정보 조회 - 단일 리뷰`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)
            val order = TestFixtures.createTestOrder(
                userId = user.id.value,
                productId = product.id.value
            )

            query {
                orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
            }

            // 리뷰 작성
            val request = ReviewRequest(
                productId = product.id.value,
                orderId = order.id.value,
                rating = 5,
                content = "좋습니다"
            )
            reviewService.createReview(user.id.value, request)

            val summary = reviewService.getReviewSummary(product.id.value)

            assertNotNull(summary)
            assertEquals(1, summary.totalReviews)
            assertTrue(summary.avgRating > 0.0)
        }
    }

    @Test
    fun `평균 별점 계산 - 여러 리뷰`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            // 3명의 사용자가 각각 리뷰 작성
            val ratings = listOf(5, 4, 3)
            ratings.forEachIndexed { index, rating ->
                val user = TestFixtures.createTestUser(
                    email = "user${index}@example.com",
                    username = "user$index"
                )
                val order = TestFixtures.createTestOrder(
                    userId = user.id.value,
                    productId = product.id.value
                )
                query {
                    orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
                }

                val request = ReviewRequest(
                    productId = product.id.value,
                    orderId = order.id.value,
                    rating = rating,
                    content = "리뷰 $rating"
                )
                reviewService.createReview(user.id.value, request)
            }

            val summary = reviewService.getReviewSummary(product.id.value)

            assertNotNull(summary)
            assertEquals(3, summary.totalReviews)
            assertEquals(4.0, summary.avgRating) // (5+4+3) / 3 = 4.0
        }
    }

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
                query {
                    orderRepository.updateOrderStatus(order.id.value, OrderStatus.DELIVERED)
                }

                val request = ReviewRequest(
                    productId = product.id.value,
                    orderId = order.id.value,
                    rating = rating,
                    content = "리뷰 $rating"
                )
                reviewService.createReview(user.id.value, request)
            }

            val summary = reviewService.getReviewSummary(product.id.value)

            assertNotNull(summary)
            assertEquals(5, summary.totalReviews)
            assertTrue(summary.ratingDistribution.isNotEmpty())
        }
    }
}
