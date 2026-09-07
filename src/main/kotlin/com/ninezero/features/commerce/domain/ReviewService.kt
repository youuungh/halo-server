package com.ninezero.features.commerce.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.ReviewSortType
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.exception.NotFoundException
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
import com.ninezero.features.user.toSummaryResponse
import io.ktor.server.plugins.*
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
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val cacheService: CacheService,
    private val productService: ProductService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    // 리뷰 생성
    suspend fun createReview(userId: Int, request: ReviewRequest): ReviewResponse {
        val content = ValidationUtils.sanitizeHtml(request.content)

        if (request.rating !in Constants.Commerce.MIN_REVIEW_RATING..Constants.Commerce.MAX_REVIEW_RATING) {
            throw BadRequestException(Errors.Commerce.Review.REVIEW_RATING_INVALID)
        }

        ValidationUtils.validateReviewContent(content)

        if (request.images.size > Constants.Commerce.MAX_REVIEW_IMAGES) {
            throw BadRequestException(Errors.Commerce.Review.REVIEW_IMAGES_COUNT_EXCEEDED)
        }

        val (response, creatorId) = query {
            val order = requireOwnedOrder(
                orderRepository = orderRepository,
                orderId = request.orderId,
                userId = userId,
                errorMessage = Errors.Commerce.Review.REVIEW_ONLY_OWN_ORDER,
                onForbidden = ::ForbiddenException
            )

            if (order.status != OrderStatus.DELIVERED) {  // DELIVERED 주문만
                throw BadRequestException(Errors.Commerce.Review.REVIEW_ORDER_NOT_DELIVERED)
            }

            val deliveredAt = order.actualDeliveryDate ?: order.updatedAt
            val elapsedDays = calcDaysBetween(deliveredAt, nowUtc())
            if (elapsedDays > Constants.Commerce.REVIEW_WRITE_DEADLINE_DAYS) {  // 배송 후 작성기간 검증
                throw BadRequestException(Errors.Commerce.Review.REVIEW_WRITE_DEADLINE_EXCEEDED)
            }

            val orderItems = orderRepository.findOrderItems(request.orderId)
            val hasProduct = orderItems.any { it.productId == request.productId }

            if (!hasProduct) {
                throw BadRequestException(Errors.Commerce.Review.REVIEW_PRODUCT_NOT_IN_ORDER)
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
                throw BadRequestException(Errors.Commerce.Review.REVIEW_ORDER_CANCELLED)
            }

            val createdReview = reviewRepository.createReview(
                userId = userId,
                productId = request.productId,
                orderId = request.orderId,
                rating = request.rating,
                content = content.trim(),
                images = request.images
            )

            val authorSummary = userRepository.findUserById(userId)?.toSummaryResponse()
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            createdReview.toReviewResponse(authorSummary) to product.creatorId
        }

        refreshProductReviewStats(request.productId)

        // 알림 전송
        if (creatorId != userId) {
            coroutineScope.launch {
                try {
                    notificationService.sendProductReviewedNotification(
                        creatorId = creatorId,
                        userId = userId,
                        productId = request.productId,
                        reviewId = response.id
                    )
                } catch (e: Exception) {
                    logger.warn("리뷰 알림 전송 실패: {}", e.message)
                }
            }
        }

        cacheService.deletePattern(CacheKeys.Patterns.reviews(request.productId))
        cacheService.delete(CacheKeys.reviewSummary(request.productId))

        return response
    }

    // 리뷰 조회
    suspend fun getReviewsByProduct(
        productId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sortBy: ReviewSortType = ReviewSortType.RECENT
    ): ReviewListResponse {
        val cacheKey = CacheKeys.reviews(productId, page, limit, sortBy.toString())
        cacheService.getJson<ReviewListResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            productRepository.findProductById(productId)
                ?: throw NotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

            val (validPage, validLimit) = validatePaginationParams(page, limit)

            val reviewsWithAuthors = reviewRepository.findReviewsWithAuthors(
                productId,
                validPage,
                validLimit,
                sortBy
            )
            val totalCount = reviewRepository.countReviewsByProductId(productId).toInt()
            val avgRating = reviewRepository.getAvgRating(productId)

            val reviewResponses = reviewsWithAuthors.map { reviewWithAuthor ->
                val authorSummary = reviewWithAuthor.author.toSummaryResponse()
                reviewWithAuthor.review.toReviewResponse(authorSummary)
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            ReviewListResponse(
                reviews = createPagedResponse(reviewResponses, pagination),
                avgRating = avgRating,
                totalReviews = totalCount
            )
        }

        cacheService.setJson(cacheKey, response, ttl = 15.minutes)

        return response
    }

    suspend fun getMyReviews(
        userId: Int,
        requestUserId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): ReviewListResponse = query {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        val reviews = reviewRepository.findReviewsByUserId(userId, validPage, validLimit)

        // 작성자는 현재 유저 본인이므로 한 번만 조회
        val author = userRepository.findUserById(userId)?.toSummaryResponse()
            ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

        // 삭제 상품 포함
        val productIds = reviews.map { it.productId }.distinct()
        val products = productRepository.findProductsByIdsWithDeleted(productIds).associateBy { it.id.value }
        val accessMap = productService.getProductAccessMap(requestUserId, products.values)

        val reviewResponses = reviews.map { review ->
            val product = products[review.productId]
            val productImageUrl = product?.imageUrls?.decodeJsonToList()?.firstOrNull()
            val canAccess = product?.let { accessMap[it.id.value] ?: false } ?: false
            review.toReviewResponse(
                author = author,
                productName = product?.name,
                productImageUrl = productImageUrl,
                canAccess = canAccess
            )
        }
        val totalCount = reviewRepository.countReviewsByUserId(userId).toInt()

        val pagination = PaginationInfo(validPage, validLimit, totalCount)
        ReviewListResponse(
            reviews = createPagedResponse(reviewResponses, pagination),
            avgRating = 0.0,
            totalReviews = totalCount
        )
    }

    suspend fun getReviewById(reviewId: Int): ReviewResponse {
        val cacheKey = CacheKeys.review(reviewId)
        cacheService.getJson<ReviewResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val review = reviewRepository.findReviewById(reviewId)
                ?: throw NotFoundException(Errors.Commerce.Review.REVIEW_NOT_FOUND)

            val author = userRepository.findUserById(review.userId)?.toSummaryResponse()
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            review.toReviewResponse(author)
        }

        cacheService.setJson(cacheKey, response, ttl = 30.minutes)

        return response
    }

    // 리뷰 수정
    suspend fun updateReview(reviewId: Int, userId: Int, request: UpdateReviewRequest): ReviewResponse {
        val content = request.content?.let { ValidationUtils.sanitizeHtml(it) }

        content?.let {
            ValidationUtils.validateReviewContent(it)
        }

        request.rating?.let {
            if (it !in Constants.Commerce.MIN_REVIEW_RATING..Constants.Commerce.MAX_REVIEW_RATING) {
                throw BadRequestException(Errors.Commerce.Review.REVIEW_RATING_INVALID)
            }
        }

        request.images?.let {
            if (it.size > Constants.Commerce.MAX_REVIEW_IMAGES) {
                throw BadRequestException(Errors.Commerce.Review.REVIEW_IMAGES_COUNT_EXCEEDED)
            }
        }

        val (response, productId, removedImages) = query {
            val review = verifyReviewOwnership(
                reviewId = reviewId,
                userId = userId,
                errorMessage = Errors.Commerce.Review.REVIEW_UPDATE_PERMISSION_DENIED
            )

            // 교체로 빠진 이미지 스냅샷
            val removed = request.images?.let { newImages ->
                review.images.decodeJsonToList()
                    .filterNot { it in newImages }
                    .filterNot { fileUploadService.isSharedSeedFile(it) }
            } ?: emptyList()

            val updated = reviewRepository.updateReview(
                reviewId = reviewId,
                rating = request.rating,
                content = content?.trim(),
                images = request.images
            ) ?: throw InternalServerException(Errors.Commerce.Review.REVIEW_UPDATE_FAILED)

            val authorSummary = userRepository.findUserById(userId)?.toSummaryResponse()
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            Triple(updated.toReviewResponse(authorSummary), review.productId, removed)
        }

        // 평점이 바뀐 경우에만 상품 통계 재계산
        if (request.rating != null) refreshProductReviewStats(productId)

        invalidateReviewCaches(reviewId, productId)

        // 교체로 빠진 이미지 파일 삭제
        removedImages.forEach { url ->
            try {
                fileUploadService.deleteFileIfSupabase(url)
            } catch (e: Exception) {
                logger.warn("리뷰 이미지 삭제 실패: {} - {}", url, e.message)
            }
        }

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

        val kept = keepImages ?: emptyList()
        if (kept.size + (newFiles?.size ?: 0) > Constants.Commerce.MAX_REVIEW_IMAGES) {
            throw BadRequestException(Errors.Commerce.Review.REVIEW_IMAGES_COUNT_EXCEEDED)
        }

        val uploadedNewFiles = mutableListOf<String>()
        try {
            if (!newFiles.isNullOrEmpty()) {
                if (newContentTypes == null || newFiles.size != newContentTypes.size) {
                    throw BadRequestException(Errors.File.IMAGE_CONTENT_TYPE_MISMATCH)
                }

                newFiles.forEach { imageData ->
                    if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.REVIEW_IMAGE)) {
                        throw ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
                    }
                }

                val extensions = newContentTypes.map { imageProcessingService.getFileExtension(it) }
                uploadedNewFiles.addAll(fileUploadService.uploadReviewImages(reviewId, newFiles, extensions))
            }

            return updateReview(
                reviewId = reviewId,
                userId = userId,
                request = UpdateReviewRequest(
                    rating = rating,
                    content = content,
                    images = kept + uploadedNewFiles
                )
            )
        } catch (e: Exception) {
            // 실패 시 미참조 업로드 파일 수거
            rollbackReviewImages(uploadedNewFiles)
            throw e
        }
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
            orderId = orderId ?: throw BadRequestException(Errors.Commerce.Order.INVALID_ORDER_ID),
            productId = productId,
            rating = rating ?: throw BadRequestException(Errors.Commerce.Review.REVIEW_RATING_INVALID),
            content = content.orEmpty()
        )

        if (files.isEmpty()) return createReview(userId, request)

        if (files.size != contentTypes.size) {
            throw BadRequestException(Errors.File.IMAGE_CONTENT_TYPE_MISMATCH)
        }
        if (files.size > Constants.Commerce.MAX_REVIEW_IMAGES) {
            throw BadRequestException(Errors.Commerce.Review.REVIEW_IMAGES_COUNT_EXCEEDED)
        }
        // 리뷰 생성 전에 이미지 검증
        files.forEach { imageData ->
            if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.REVIEW_IMAGE)) {
                throw ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
            }
        }

        val created = createReview(userId, request)

        val uploaded = mutableListOf<String>()
        try {
            val extensions = contentTypes.map { imageProcessingService.getFileExtension(it) }
            uploaded.addAll(fileUploadService.uploadReviewImages(created.id, files, extensions))

            return updateReview(
                reviewId = created.id,
                userId = userId,
                request = UpdateReviewRequest(images = uploaded)
            )
        } catch (e: Exception) {  // 실패 시 생성한 리뷰까지 롤백
            rollbackReviewImages(uploaded)
            // 이미지 없는 리뷰만 남는 것 방지
            runCatching { deleteReview(created.id, userId) }
                .onFailure { logger.warn("리뷰 생성 롤백 실패: reviewId={} - {}", created.id, it.message) }
            throw e
        }
    }

    suspend fun uploadReviewImages(
        reviewId: Int,
        userId: Int,
        images: List<ByteArray>,
        contentTypes: List<String>
    ): List<String> {
        val uploadedNewFiles = mutableListOf<String>()

        try {
            val review = query {
                verifyReviewOwnership(
                    reviewId = reviewId,
                    userId = userId,
                    errorMessage = Errors.Commerce.Review.REVIEW_IMAGE_UPLOAD_PERMISSION_DENIED
                )
            }

            if (images.isEmpty()) {
                throw BadRequestException(Errors.File.MIN_IMAGE_REQUIRED)
            }

            if (images.size != contentTypes.size) {
                throw BadRequestException(Errors.File.IMAGE_CONTENT_TYPE_MISMATCH)
            }

            val currentImages = review.images.decodeJsonToList()
            if (currentImages.size + images.size > Constants.Commerce.MAX_REVIEW_IMAGES) {  // 최대 개수 검증
                throw BadRequestException(Errors.Commerce.Review.REVIEW_IMAGES_COUNT_EXCEEDED)
            }

            images.forEach { imageData ->
                if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.REVIEW_IMAGE)) {
                    throw BadRequestException(Errors.File.IMAGE_VALIDATION_FAILED)
                }
            }

            val extensions = contentTypes.map { imageProcessingService.getFileExtension(it) }
            val newImageUrls = fileUploadService.uploadReviewImages(reviewId, images, extensions)
            uploadedNewFiles.addAll(newImageUrls)

            val updatedImages = currentImages + newImageUrls
            val updated = query {
                reviewRepository.updateReview(
                    reviewId = reviewId,
                    rating = null,
                    content = null,
                    images = updatedImages
                )
            }

            if (updated == null) {  // DB 반영 실패 시 롤백
                rollbackReviewImages(uploadedNewFiles)
                throw InternalServerException(Errors.Commerce.Review.REVIEW_UPDATE_FAILED)
            }

            invalidateReviewCaches(reviewId, review.productId)

            return newImageUrls

        } catch (e: Exception) {
            rollbackReviewImages(uploadedNewFiles)
            throw e
        }
    }

    suspend fun deleteReviewImage(reviewId: Int, userId: Int, imageUrl: String) {
        val review = query {
            verifyReviewOwnership(
                reviewId = reviewId,
                userId = userId,
                errorMessage = Errors.Commerce.Review.REVIEW_IMAGE_DELETE_PERMISSION_DENIED
            )
        }

        val currentImages = review.images.decodeJsonToList()
        if (!currentImages.contains(imageUrl)) {
            throw NotFoundException(Errors.File.IMAGE_NOT_FOUND)
        }

        val updatedImages = currentImages.filter { it != imageUrl }
        query {
            reviewRepository.updateReview(
                reviewId = reviewId,
                rating = null,
                content = null,
                images = updatedImages
            )
        }

        // 파일 삭제는 커밋 후
        if (!fileUploadService.isSharedSeedFile(imageUrl)) {
            fileUploadService.deleteFileIfSupabase(imageUrl)
        }

        invalidateReviewCaches(reviewId, review.productId)
    }

    // 리뷰 삭제
    suspend fun deleteReview(reviewId: Int, userId: Int) {
        val (deleted, imageUrls, productId) = query {
            val review = verifyReviewOwnership(
                reviewId = reviewId,
                userId = userId,
                errorMessage = Errors.Commerce.Review.REVIEW_DELETE_PERMISSION_DENIED
            )

            // 시드 공용 파일 제외
            val images = review.images.decodeJsonToList().filterNot { fileUploadService.isSharedSeedFile(it) }
            val result = reviewRepository.deleteReview(reviewId)
            Triple(result, images, review.productId)
        }

        if (!deleted) {
            throw InternalServerException(Errors.Commerce.Review.REVIEW_DELETE_FAILED)
        }

        refreshProductReviewStats(productId)

        imageUrls.forEach { url ->
            try {
                fileUploadService.deleteFileIfSupabase(url)
            } catch (e: Exception) {
                logger.warn("리뷰 이미지 삭제 실패: {} - {}", url, e.message)
            }
        }

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

        uploadedUrls.forEach { url ->
            try {
                fileUploadService.deleteFileIfSupabase(url)
            } catch (e: Exception) {
                logger.warn("리뷰 이미지 롤백 실패: {} - {}", url, e.message)
            }
        }
    }
}
