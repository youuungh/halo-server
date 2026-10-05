package com.ninezero.features.commerce.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.ReviewSortType
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.commerce.data.OrderRepository
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.data.ReviewRepository
import com.ninezero.features.commerce.presentation.models.request.ReviewRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateReviewRequest
import com.ninezero.features.commerce.presentation.models.response.ReviewListResponse
import com.ninezero.features.commerce.presentation.models.response.ReviewResponse
import com.ninezero.features.commerce.presentation.models.response.ReviewSummaryResponse
import com.ninezero.features.commerce.toReviewResponse
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toUserSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.minutes

class ReviewService(
    private val reviewRepository: ReviewRepository,
    private val orderRepository: OrderRepository,
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val productService: ProductService,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val cacheService: CacheService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    // 리뷰 생성
    suspend fun createReview(userId: Int, request: ReviewRequest): ReviewResponse {
        val (response, creatorId) = insertReview(userId, request)
        publishReviewCreated(userId, request.productId, response.id, creatorId)
        return response
    }

    /** 리뷰 생성 + 이미지 업로드 */
    suspend fun createReviewWithImages(
        userId: Int,
        productId: Int,
        orderId: Int?,
        rating: Int?,
        content: String?,
        files: List<ByteArray>,
        contentTypes: List<String>
    ): ReviewResponse {
        val request = ReviewRequest(  // reviewId 경로라 생성 먼저
            orderId = orderId ?: throw InvalidInputException(Errors.Commerce.Order.INVALID_ORDER_ID),
            productId = productId,
            rating = rating ?: throw InvalidInputException(Errors.Commerce.Review.REVIEW_RATING_INVALID),
            content = content.orEmpty()
        )

        if (files.isEmpty()) return createReview(userId, request)

        if (files.size != contentTypes.size) {
            throw InvalidInputException(Errors.File.IMAGE_CONTENT_TYPE_MISMATCH)
        }
        if (files.size > Constants.Commerce.MAX_REVIEW_IMAGES) {
            throw InvalidInputException(Errors.Commerce.Review.REVIEW_IMAGES_COUNT_EXCEEDED)
        }
        // 리뷰 생성 전에 이미지 검증
        files.forEach { imageData ->
            if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.REVIEW_IMAGE)) {
                throw ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
            }
        }

        val (created, creatorId) = insertReview(userId, request)

        val uploaded = mutableListOf<String>()
        val response = try {
            uploaded.addAll(fileUploadService.uploadReviewImages(created.id, files))

            applyReviewUpdate(
                reviewId = created.id,
                userId = userId,
                request = UpdateReviewRequest(images = uploaded),
                serverUploaded = uploaded.toSet()
            )
        } catch (e: Exception) {  // 실패 시 생성한 리뷰까지 롤백
            rollbackReviewImages(uploaded)
            try {
                query { reviewRepository.hardDeleteReview(created.id) }
            } catch (rollbackError: Exception) {
                logger.warn("리뷰 생성 롤백 실패: reviewId={} - {}", created.id, rollbackError.message)
            }
            throw e
        }

        // 사진까지 붙은 뒤에만 알림
        publishReviewCreated(userId, productId, created.id, creatorId)
        return response
    }

    /** 리뷰 저장, 통계·알림·캐시는 확정 뒤 */
    private suspend fun insertReview(userId: Int, request: ReviewRequest): Pair<ReviewResponse, Int> {
        val content = request.content.trim()

        if (request.rating !in Constants.Commerce.MIN_REVIEW_RATING..Constants.Commerce.MAX_REVIEW_RATING) {
            throw InvalidInputException(Errors.Commerce.Review.REVIEW_RATING_INVALID)
        }

        ValidationUtils.validateReviewContent(content)

        val (response, creatorId) = query {
            val order = requireOwnedOrder(
                orderRepository = orderRepository,
                orderId = request.orderId,
                userId = userId,
                errorMessage = Errors.Commerce.Review.REVIEW_ONLY_OWN_ORDER
            )

            if (order.status != OrderStatus.DELIVERED) {  // DELIVERED 주문만
                throw InvalidInputException(Errors.Commerce.Review.REVIEW_ORDER_NOT_DELIVERED)
            }

            val deliveredAt = order.actualDeliveryDate ?: order.updatedAt
            val elapsedDays = calcDaysBetween(deliveredAt, nowUtc())
            if (elapsedDays > Constants.Commerce.REVIEW_WRITE_DEADLINE_DAYS) {  // 배송 후 작성기간 검증
                throw InvalidInputException(Errors.Commerce.Review.REVIEW_WRITE_DEADLINE_EXCEEDED)
            }

            val orderItems = orderRepository.findOrderItems(request.orderId)
            val hasProduct = orderItems.any { it.productId == request.productId }

            if (!hasProduct) {
                throw InvalidInputException(Errors.Commerce.Review.REVIEW_PRODUCT_NOT_IN_ORDER)
            }

            val existingReview = reviewRepository.findReviewByOrderProduct(request.orderId, request.productId)
            if (existingReview != null) {
                throw ConflictException(Errors.Commerce.Review.REVIEW_ALREADY_EXISTS)
            }

            val product = productRepository.findProductById(request.productId)
                ?: throw NotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

            // 취소된 배송 그룹 상품은 리뷰 불가
            val shipment = orderRepository.findCreatorShipment(request.orderId, product.creatorId)
            if (shipment?.status == OrderStatus.CANCELLED) {
                throw InvalidInputException(Errors.Commerce.Review.REVIEW_ORDER_CANCELLED)
            }

            val createdReview = reviewRepository.createReview(
                userId = userId,
                productId = request.productId,
                orderId = request.orderId,
                rating = request.rating,
                content = content.trim(),
                images = emptyList()
            )

            val authorSummary = userRepository.findUserById(userId)?.toUserSummaryResponse()
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            createdReview.toReviewResponse(authorSummary) to product.creatorId
        }

        return response to creatorId
    }

    /** 리뷰 확정 뒤 통계·알림·캐시 */
    private suspend fun publishReviewCreated(userId: Int, productId: Int, reviewId: Int, creatorId: Int) {
        refreshProductReviewStats(productId)

        if (creatorId != userId) {
            coroutineScope.launch {
                try {
                    notificationService.sendProductReviewedNotification(
                        creatorId = creatorId,
                        userId = userId,
                        productId = productId,
                        reviewId = reviewId
                    )
                } catch (e: Exception) {
                    logger.warn("리뷰 알림 전송 실패: {}", e.message)
                }
            }
        }

        cacheService.deletePattern(CacheKeys.Patterns.reviews(productId))
        cacheService.delete(CacheKeys.reviewSummary(productId))
    }

    // 리뷰 조회
    suspend fun getReviewsByProduct(
        productId: Int,
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sortBy: ReviewSortType = ReviewSortType.RECENT
    ): ReviewListResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        val cacheKey = CacheKeys.reviews(productId, pageCursor?.encode().orEmpty(), validLimit, sortBy.toString())
        cacheService.getJson<ReviewListResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            productRepository.findProductById(productId)
                ?: throw NotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

            val (reviewsWithAuthors, nextCursor) = reviewRepository.findReviewsWithAuthors(productId, pageCursor, validLimit + 1, sortBy)
                .toCursorPage(validLimit) { (review, _) ->
                    if (sortBy == ReviewSortType.RECENT) PageCursor.of(review.createdAt, review.id.value)
                    else PageCursor.of(review.rating, review.createdAt, review.id.value)
                }

            val reviewResponses = reviewsWithAuthors.map { reviewWithAuthor ->
                val authorSummary = reviewWithAuthor.author.toUserSummaryResponse()
                reviewWithAuthor.review.toReviewResponse(authorSummary)
            }

            CursorResponse(reviewResponses, nextCursor, hasNext = nextCursor != null)
        }

        cacheService.setJson(cacheKey, response, ttl = 15.minutes)

        return response
    }

    suspend fun getUserReviews(
        userId: Int,
        currentUserId: Int,
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): ReviewListResponse = query {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        val (reviews, nextCursor) = reviewRepository.findReviewsByUserId(userId, pageCursor, validLimit + 1)
            .toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }

        // 작성자가 모두 같아 한 번만 조회
        val author = userRepository.findUserById(userId)?.toUserSummaryResponse()
            ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

        // 삭제 상품 포함
        val productIds = reviews.map { it.productId }.distinct()
        val products = productRepository.findProductsByIdsWithDeleted(productIds).associateBy { it.id.value }
        val accessMap = productService.getProductAccessMap(currentUserId, products.values)

        val reviewResponses = reviews.map { review ->
            val product = products[review.productId]
            val productImageUrl = product?.imageUrls?.decodeJsonToList()?.firstOrNull()
            val canAccess = product != null && accessMap[product.id.value] == true
            review.toReviewResponse(
                author = author,
                productName = product?.name,
                productImageUrl = productImageUrl,
                canAccess = canAccess
            )
        }
        CursorResponse(reviewResponses, nextCursor, hasNext = nextCursor != null)
    }

    suspend fun getReviewById(reviewId: Int): ReviewResponse {
        val cacheKey = CacheKeys.review(reviewId)
        cacheService.getJson<ReviewResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val review = reviewRepository.findReviewById(reviewId)
                ?: throw NotFoundException(Errors.Commerce.Review.REVIEW_NOT_FOUND)

            val author = userRepository.findUserById(review.userId)?.toUserSummaryResponse()
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            review.toReviewResponse(author)
        }

        cacheService.setJson(cacheKey, response, ttl = 30.minutes)

        return response
    }

    // 리뷰 수정
    suspend fun updateReview(reviewId: Int, userId: Int, request: UpdateReviewRequest): ReviewResponse =
        applyReviewUpdate(reviewId, userId, request, serverUploaded = emptySet())

    private suspend fun applyReviewUpdate(
        reviewId: Int,
        userId: Int,
        request: UpdateReviewRequest,
        serverUploaded: Set<String>
    ): ReviewResponse {
        val content = request.content?.trim()

        content?.let {
            ValidationUtils.validateReviewContent(it)
        }

        request.rating?.let {
            if (it !in Constants.Commerce.MIN_REVIEW_RATING..Constants.Commerce.MAX_REVIEW_RATING) {
                throw InvalidInputException(Errors.Commerce.Review.REVIEW_RATING_INVALID)
            }
        }

        request.images?.let {
            if (it.size > Constants.Commerce.MAX_REVIEW_IMAGES) {
                throw InvalidInputException(Errors.Commerce.Review.REVIEW_IMAGES_COUNT_EXCEEDED)
            }
        }

        val (response, productId, removedImages) = query {
            val review = verifyReviewOwnership(
                reviewId = reviewId,
                userId = userId,
                errorMessage = Errors.Commerce.Review.REVIEW_UPDATE_PERMISSION_DENIED
            )

            // 저장돼 있던 이미지와 이번에 서버가 올린 파일만 허용
            request.images?.let { newImages ->
                val allowed = review.images.decodeJsonToList().toSet() + serverUploaded
                if (newImages.any { it !in allowed }) {
                    throw InvalidInputException(Errors.File.FILE_NOT_OWNED)
                }
            }

            // 교체로 빠진 이미지 스냅샷
            val removed = request.images?.let { newImages ->
                review.images.decodeJsonToList().filterNot { it in newImages }
            }.orEmpty()

            val updated = reviewRepository.updateReview(
                reviewId = reviewId,
                rating = request.rating,
                content = content?.trim(),
                images = request.images
            ) ?: throw InternalServerException(Errors.Commerce.Review.REVIEW_UPDATE_FAILED)

            val authorSummary = userRepository.findUserById(userId)?.toUserSummaryResponse()
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            Triple(updated.toReviewResponse(authorSummary), review.productId, removed)
        }

        // 평점이 바뀐 경우에만 상품 통계 재계산
        if (request.rating != null) refreshProductReviewStats(productId)

        invalidateReviewCaches(reviewId, productId)

        // 교체로 빠진 이미지 파일 삭제
        fileUploadService.deleteByUrls(removedImages)

        return response
    }

    /** 리뷰 수정 + 이미지 교체 */
    suspend fun updateReviewWithImages(
        reviewId: Int,
        userId: Int,
        rating: Int?,
        content: String?,
        keepImages: List<String>?,
        newFiles: List<ByteArray>?,
        newContentTypes: List<String>?
    ): ReviewResponse {
        // null과 빈 이미지 목록 구분 필수
        if (keepImages == null && newFiles.isNullOrEmpty()) {
            return updateReview(reviewId, userId, UpdateReviewRequest(rating = rating, content = content))
        }

        // 유지 목록이 없으면 기존 이미지 뒤에 추가
        val kept = keepImages ?: query {
            verifyReviewOwnership(
                reviewId = reviewId,
                userId = userId,
                errorMessage = Errors.Commerce.Review.REVIEW_UPDATE_PERMISSION_DENIED
            ).images.decodeJsonToList()
        }
        if (kept.size + (newFiles?.size ?: 0) > Constants.Commerce.MAX_REVIEW_IMAGES) {
            throw InvalidInputException(Errors.Commerce.Review.REVIEW_IMAGES_COUNT_EXCEEDED)
        }

        val uploadedNewFiles = mutableListOf<String>()
        try {
            if (!newFiles.isNullOrEmpty()) {
                if (newContentTypes == null || newFiles.size != newContentTypes.size) {
                    throw InvalidInputException(Errors.File.IMAGE_CONTENT_TYPE_MISMATCH)
                }

                newFiles.forEach { imageData ->
                    if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.REVIEW_IMAGE)) {
                        throw ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
                    }
                }

                uploadedNewFiles.addAll(fileUploadService.uploadReviewImages(reviewId, newFiles))
            }

            return applyReviewUpdate(
                reviewId = reviewId,
                userId = userId,
                request = UpdateReviewRequest(
                    rating = rating,
                    content = content,
                    images = kept + uploadedNewFiles
                ),
                serverUploaded = uploadedNewFiles.toSet()
            )
        } catch (e: Exception) {
            // 실패 시 미참조 업로드 파일 수거
            rollbackReviewImages(uploadedNewFiles)
            throw e
        }
    }

    // 리뷰 삭제
    suspend fun deleteReview(reviewId: Int, userId: Int) {
        val (deleted, imageUrls, productId) = query {
            val review = verifyReviewOwnership(
                reviewId = reviewId,
                userId = userId,
                errorMessage = Errors.Commerce.Review.REVIEW_DELETE_PERMISSION_DENIED
            )

            val images = review.images.decodeJsonToList()
            val result = reviewRepository.deleteReview(reviewId)
            Triple(result, images, review.productId)
        }

        if (!deleted) {
            throw InternalServerException(Errors.Commerce.Review.REVIEW_DELETE_FAILED)
        }

        refreshProductReviewStats(productId)

        fileUploadService.deleteByUrls(imageUrls)

        invalidateReviewCaches(reviewId, productId)
    }

    // 리뷰 요약
    suspend fun getReviewSummary(productId: Int): ReviewSummaryResponse {
        val cacheKey = CacheKeys.reviewSummary(productId)
        cacheService.getJson<ReviewSummaryResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val totalReviews = reviewRepository.countReviewsByProductId(productId).toInt()
            val avgRating = reviewRepository.getAvgRating(productId)
            val ratingDistribution = reviewRepository.getRatingDistribution(productId)

            ReviewSummaryResponse(
                productId = productId,
                avgRating = avgRating,
                totalReviews = totalReviews,
                ratingDistribution = ratingDistribution
            )
        }

        cacheService.setJson(cacheKey, response, ttl = 15.minutes)

        return response
    }

    // 공통 보조
    private suspend fun invalidateReviewCaches(reviewId: Int, productId: Int) {
        cacheService.delete(CacheKeys.review(reviewId))
        cacheService.deletePattern(CacheKeys.Patterns.reviews(productId))
        cacheService.delete(CacheKeys.reviewSummary(productId))
    }

    /** 상품 리뷰 통계 재계산 */
    private suspend fun refreshProductReviewStats(productId: Int) {
        try {
            query {
                val count = reviewRepository.countReviewsByProductId(productId).toInt()
                val avg = reviewRepository.getAvgRating(productId)
                productRepository.updateReviewStats(
                    productId = productId,
                    reviewCount = count,
                    rating = BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP)
                )
            }
        } catch (e: Exception) {
            // 통계 갱신 실패해도 리뷰 작업은 성공 처리
            logger.warn("상품 리뷰 통계 갱신 실패: productId={}, error={}", productId, e.message)
        }
    }

    private suspend fun verifyReviewOwnership(
        reviewId: Int,
        userId: Int,
        errorMessage: String
    ) = reviewRepository.findReviewById(reviewId)
        ?.also { review ->
            if (review.userId != userId) {
                throw ForbiddenException(errorMessage)
            }
        }
        ?: throw NotFoundException(Errors.Commerce.Review.REVIEW_NOT_FOUND)

    private suspend fun rollbackReviewImages(uploadedUrls: List<String>) {
        if (uploadedUrls.isEmpty()) return

        fileUploadService.deleteByUrls(uploadedUrls)
    }
}
