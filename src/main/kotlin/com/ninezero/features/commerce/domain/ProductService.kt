package com.ninezero.features.commerce.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.presentation.models.DetailContentBlock
import com.ninezero.features.commerce.presentation.models.request.ProductRequest
import com.ninezero.features.commerce.presentation.models.request.ProductSearchRequest
import com.ninezero.features.commerce.presentation.models.request.SetProductDealRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateProductRequest
import com.ninezero.features.commerce.presentation.models.response.ProductListResponse
import com.ninezero.features.commerce.presentation.models.response.ProductResponse
import com.ninezero.features.commerce.extractDetailContentImageUrls
import com.ninezero.features.commerce.toProductResponse
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.tag.data.TagRepository
import com.ninezero.features.commerce.presentation.models.response.CreatorStoreResponse
import com.ninezero.features.commerce.presentation.models.response.ProductTagSectionResponse
import com.ninezero.features.tag.toTagResponse
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes

class ProductService(
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
    private val followRepository: FollowRepository,
    private val tagRepository: TagRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val notificationService: NotificationService,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val cacheService: CacheService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    // 상품 생성
    suspend fun createProduct(userId: Int, request: ProductRequest): ProductResponse {
        val name = ValidationUtils.sanitizeHtml(request.name)
        val description = ValidationUtils.sanitizeHtml(request.description)
        val brandName = request.brandName?.let { ValidationUtils.sanitizeHtml(it) }

        ValidationUtils.validateProductName(name)
        ValidationUtils.validateProductDescription(description)

        val price = BigDecimal(request.price)
        ValidationUtils.validatePositivePrice(price)

        val originalPrice = request.originalPrice?.let { BigDecimal(it) }
        ValidationUtils.validateOriginalPrice(originalPrice, price)
        ValidationUtils.validateStock(request.stock)

        val imageUrls = (request.imageUrls ?: emptyList()).validateProductImages()
        ValidationUtils.validateProductImages(imageUrls)

        val detailContentJson = request.detailContent?.let { Json.encodeToString(it) }

        val tags = request.tags ?: emptyList()
        ValidationUtils.validateProductTags(tags)

        request.tagIds?.let { tagIds ->
            if (request.sectionTagId != null && !tagIds.contains(request.sectionTagId)) {
                throw InvalidTagException(Errors.Social.Tag.SECTION_TAG_NOT_IN_SELECTED_TAGS)
            }
        }

        val (response, productId) = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
                throw PermissionDeniedException(Errors.Commerce.Product.PRODUCT_REGISTRATION_PERMISSION_DENIED)
            }

            if (request.requiredTier != SubscriptionPlanTier.FREE) {  // 유료 tier면 해당 tier 활성 플랜 필요
                val activePlans = planRepository.findActivePlansByCreator(userId, 1, 100)
                val hasMatchingPlan = activePlans.any { it.tier == request.requiredTier }

                if (!hasMatchingPlan) {
                    throw SubscriptionPlanRequiredException(
                        Errors.Commerce.Product.PLAN_CREATE_REQUIRED.format(request.requiredTier.toDisplayName())
                    )
                }
            }

            val createdProduct = productRepository.createProduct(
                creatorId = userId,
                name = name.trim(),
                description = description.trim(),
                price = price,
                originalPrice = originalPrice,
                stock = request.stock,
                categoryId = request.categoryId,
                brandName = brandName?.trim(),
                imageUrls = imageUrls,
                detailContent = detailContentJson,
                tags = tags,
                requiredTier = request.requiredTier
            )

            request.tagIds?.let { tagIds ->
                tagRepository.attachTagsToProduct(
                    productId = createdProduct.id.value,
                    tagIds = tagIds,
                    sectionTagId = request.sectionTagId
                )
            }

            val creatorSummary = user.toSummaryResponse()
            val productResponse = createdProduct.toProductResponse(creatorSummary)

            productResponse to createdProduct.id.value
        }

        cacheService.deletePattern(CacheKeys.Patterns.creatorStore(userId))

        coroutineScope.launch {  // 커밋 후 팔로워 알림
            try {
                val followerIds = query {
                    followRepository.findProductNotifyFollowers(userId)
                }

                if (followerIds.isNotEmpty()) {
                    notificationService.sendNewProductNotifications(
                        creatorId = userId,
                        creatorUsername = response.creator.username,
                        productId = productId,
                        followerIds = followerIds
                    )
                }
            } catch (e: Exception) {
                logger.error("새 상품 알림 전송 실패: productId=$productId, error=${e.message}", e)
            }
        }

        return response
    }

    // 상품 조회
    suspend fun getProductById(productId: Int, currentUserId: Int?): ProductResponse {
        val cacheKey = CacheKeys.product(productId, currentUserId)
        cacheService.getJson<ProductResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            // 삭제 상품은 404
            val product = productRepository.findProductById(productId)
                ?: throw ProductNotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

            val creator = userRepository.findUserById(product.creatorId)?.toSummaryResponse()
                ?: throw CreatorNotFoundException(Errors.Commerce.Product.CREATOR_NOT_FOUND)

            // 잠금 상품도 미리보기 반환
            val hasAccess = checkProductAccess(currentUserId, product)

            productRepository.incrementViewCount(productId)

            product.toProductResponse(creator, canAccess = hasAccess)
        }

        cacheService.setJson(cacheKey, response, ttl = 30.minutes)

        return response
    }

    suspend fun getAllProducts(
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        currentUserId: Int? = null
    ): ProductListResponse {
        return query {
            val (validPage, validLimit) = validatePaginationParams(page, limit)

            val products = productRepository.findAllProducts(validPage, validLimit)
            val total = productRepository.countAllProducts()

            val userIds = products.map { it.creatorId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }
            // 접근권한 일괄 조회
            val accessMap = getProductAccessMap(currentUserId, products)

            val productResponses = products.map { product ->
                val creator = userMap[product.creatorId]?.toSummaryResponse()
                    ?: throw CreatorNotFoundException(Errors.Commerce.Product.CREATOR_NOT_FOUND)

                val canAccess = accessMap[product.id.value] ?: false
                product.toProductResponse(creator, canAccess)
            }

            val pagination = PaginationInfo(validPage, validLimit, total)
            createPagedResponse(productResponses, pagination)
        }
    }

    suspend fun getMyProducts(
        userId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): ProductListResponse {
        return query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)

            if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
                throw PermissionDeniedException(Errors.Commerce.Product.PRODUCT_PERMISSION_DENIED)
            }

            val (validPage, validLimit) = validatePaginationParams(page, limit)

            val products = productRepository.findProductsByCreator(userId, validPage, validLimit)
            val total = productRepository.countProductsByCreator(userId)
            val creator = user.toSummaryResponse()

            // 섹션 태그 일괄 조회
            val sectionTagMap = tagRepository.findSectionTagsByProducts(products.map { it.id.value })
            val productResponses = products.map { product ->
                val sectionTag = sectionTagMap[product.id.value]?.toTagResponse()
                product.toProductResponse(creator, sectionTag = sectionTag)
            }

            val pagination = PaginationInfo(validPage, validLimit, total)
            createPagedResponse(productResponses, pagination)
        }
    }

    suspend fun searchProducts(request: ProductSearchRequest, currentUserId: Int? = null): ProductListResponse {
        return query {
            val (validPage, validLimit) = validatePaginationParams(request.page, request.limit)

            val products = productRepository.searchProducts(
                query = request.query,
                categoryId = request.categoryId,
                brandName = request.brandName,
                minPrice = request.minPrice?.let { BigDecimal(it) },
                maxPrice = request.maxPrice?.let { BigDecimal(it) },
                tags = request.tags,
                page = validPage,
                limit = validLimit
            )

            val total = productRepository.countSearchResults(
                query = request.query,
                categoryId = request.categoryId,
                brandName = request.brandName,
                minPrice = request.minPrice?.let { BigDecimal(it) },
                maxPrice = request.maxPrice?.let { BigDecimal(it) },
                tags = request.tags
            )

            val userIds = products.map { it.creatorId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }
            val accessMap = getProductAccessMap(currentUserId, products)

            val productResponses = products.map { product ->
                val creator = userMap[product.creatorId]?.toSummaryResponse()
                    ?: throw CreatorNotFoundException(Errors.Commerce.Product.CREATOR_NOT_FOUND)

                val canAccess = accessMap[product.id.value] ?: false
                product.toProductResponse(creator, canAccess)
            }

            val pagination = PaginationInfo(validPage, validLimit, total)
            createPagedResponse(productResponses, pagination)
        }
    }

    // 상품 수정
    suspend fun updateProduct(productId: Int, userId: Int, request: UpdateProductRequest): ProductResponse {
        val name = request.name?.let { ValidationUtils.sanitizeHtml(it) }
        val description = request.description?.let { ValidationUtils.sanitizeHtml(it) }
        val brandName = request.brandName?.let { ValidationUtils.sanitizeHtml(it) }

        name?.let { ValidationUtils.validateProductName(it) }
        description?.let { ValidationUtils.validateProductDescription(it) }

        val price = request.price?.let { BigDecimal(it) }
        val originalPrice = request.originalPrice?.let { BigDecimal(it) }

        request.stock?.let {
            if (it < 0) {
                throw InvalidStockException(Errors.Commerce.Product.INVALID_STOCK_QUANTITY)
            }
        }

        val imageUrls = request.imageUrls?.let { urls ->
            val validated = urls.validateProductImages()
            ValidationUtils.validateProductImages(validated)
            validated
        }

        val detailContentJson = request.detailContent?.let { Json.encodeToString(it) }

        request.tags?.let { tags ->
            if (tags.size > Constants.Commerce.MAX_PRODUCT_TAGS) {
                throw ProductTagsExceededException(Errors.Commerce.Product.PRODUCT_TAGS_COUNT_EXCEEDED)
            }
        }

        if (request.status != null) {  // status는 별도 API로만 변경
            throw InvalidInputException(Errors.Commerce.Product.PRODUCT_STATUS_UPDATE_VIA_STATUS_API_ONLY)
        }

        request.tagIds?.let { tagIds ->
            if (request.sectionTagId != null && !tagIds.contains(request.sectionTagId)) {
                throw InvalidTagException(Errors.Social.Tag.SECTION_TAG_NOT_IN_SELECTED_TAGS)
            }
        }

        val (response, removedImages) = query {
            val product = verifyProductOwnership(productId, userId, Errors.Commerce.Product.PRODUCT_UPDATE_PERMISSION_DENIED)

            // 활성 타임딜 중엔 판매가를 딜가 이하로 못 내림
            if (price != null) {
                val dealPrice = product.dealPrice
                val dealEndAt = product.dealEndAt
                if (dealPrice != null && dealEndAt != null && dealEndAt > nowUtc() && dealPrice >= price) {
                    throw InvalidInputException(Errors.Commerce.Product.PRICE_BELOW_ACTIVE_DEAL)
                }
            }

            // 교체로 빠진 이미지 스냅샷
            val oldImageUrls = product.imageUrls.decodeJsonToList()
            val oldDetailUrls = extractDetailContentImageUrls(product.detailContent)
            val finalImageUrls = imageUrls ?: oldImageUrls
            val finalDetailUrls = detailContentJson?.let { extractDetailContentImageUrls(it) } ?: oldDetailUrls
            val removed = ((oldImageUrls - finalImageUrls.toSet()) + (oldDetailUrls - finalDetailUrls.toSet()))
                .filterNot { it in finalImageUrls || it in finalDetailUrls }
                .filterNot { fileUploadService.isSharedSeedFile(it) }

            request.requiredTier?.let { newTier ->
                if (newTier != SubscriptionPlanTier.FREE) {
                    val activePlans = planRepository.findActivePlansByCreator(userId, 1, 100)
                    val hasMatchingPlan = activePlans.any { it.tier == newTier }

                    if (!hasMatchingPlan) {
                        throw SubscriptionPlanRequiredException(
                            Errors.Commerce.Product.PLAN_CREATE_REQUIRED.format(newTier.toDisplayName())
                        )
                    }
                }
            }

            val updated = productRepository.updateProduct(
                productId = productId,
                name = name?.trim(),
                description = description?.trim(),
                price = price,
                originalPrice = originalPrice,
                stock = request.stock,
                categoryId = request.categoryId,
                brandName = brandName?.trim(),
                imageUrls = imageUrls,
                detailContent = detailContentJson,
                tags = request.tags,
                status = null,
                requiredTier = request.requiredTier
            ) ?: throw ProductUpdateFailedException(Errors.Commerce.Product.PRODUCT_UPDATE_FAILED)

            request.tagIds?.let { tagIds ->
                tagRepository.attachTagsToProduct(
                    productId = productId,
                    tagIds = tagIds,
                    sectionTagId = request.sectionTagId
                )
            }

            val user = userRepository.findUserById(userId)!!
            val creatorSummary = user.toSummaryResponse()

            updated.toProductResponse(creatorSummary) to removed
        }

        // 교체로 빠진 이미지 파일 삭제
        removedImages.forEach { url ->
            try {
                fileUploadService.deleteFileIfSupabase(url)
            } catch (e: Exception) {
                logger.warn("상품 이미지 삭제 실패: {} - {}", url, e.message)
            }
        }

        invalidateProductCaches(productId, userId)

        return response
    }

    // 상품 이미지
    suspend fun uploadProductImages(
        productId: Int,
        userId: Int,
        images: List<ByteArray>,
        contentTypes: List<String>,
        replaceExisting: Boolean = false
    ): List<String> {
        // 롤백용: 새로 업로드한 파일 URL 목록
        val uploadedNewFiles = mutableListOf<String>()

        try {
            val oldImageUrls = query {
                val product = verifyProductOwnership(productId, userId, Errors.Commerce.Product.PRODUCT_IMAGE_UPLOAD_PERMISSION_DENIED)

                product.imageUrls.decodeJsonToList()
            }

            if (images.isEmpty()) {
                throw MinImageRequiredException(Errors.File.MIN_IMAGE_REQUIRED)
            }

            if (images.size != contentTypes.size) {
                throw ImageContentTypeMismatchException(Errors.File.IMAGE_CONTENT_TYPE_MISMATCH)
            }

            if (images.size > Constants.Commerce.MAX_PRODUCT_IMAGES) {
                throw ProductImagesCountExceededException(Errors.Commerce.Product.PRODUCT_IMAGES_COUNT_EXCEEDED)
            }

            images.forEachIndexed { _, imageData ->
                if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.PRODUCT)) {
                    throw ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED_INDEX)
                }
            }

            val extensions = contentTypes.map { contentType ->
                imageProcessingService.getFileExtension(contentType)
            }

            val newImageUrls = fileUploadService.uploadProductImages(productId, images, extensions)
            uploadedNewFiles.addAll(newImageUrls)

            // 최종 이미지 목록 결정
            val updatedImages = if (replaceExisting) {
                newImageUrls
            } else {
                (oldImageUrls + newImageUrls).take(Constants.Commerce.MAX_PRODUCT_IMAGES)
            }

            val updated = query {
                productRepository.updateProductImages(productId, updatedImages)
            }

            if (updated == null) {
                rollbackProductImages(uploadedNewFiles)
                throw ProductUpdateFailedException(Errors.Commerce.Product.PRODUCT_UPDATE_FAILED)
            }

            // DB 업데이트 성공 후에만 기존 이미지 삭제
            if (replaceExisting) {
                oldImageUrls.forEach { oldUrl ->
                    fileUploadService.deleteFileIfSupabase(oldUrl)
                }
            }

            invalidateProductCaches(productId, userId)

            return newImageUrls

        } catch (e: Exception) {  // 실패 시 업로드 파일 롤백
            rollbackProductImages(uploadedNewFiles)
            throw e
        }
    }

    suspend fun deleteProductImage(productId: Int, userId: Int, imageUrl: String) {
        query {
            val product = verifyProductOwnership(productId, userId, Errors.Commerce.Product.PRODUCT_IMAGE_DELETE_PERMISSION_DENIED)

            val currentImages = product.imageUrls.decodeJsonToList()

            if (!currentImages.contains(imageUrl)) {
                throw ImageNotFoundException(Errors.File.IMAGE_NOT_FOUND)
            }

            val updatedImages = currentImages.filter { it != imageUrl }
            productRepository.updateProductImages(productId, updatedImages)
        }

        invalidateProductCaches(productId, userId)

        // 시드 공용 파일은 DB에서만 제거
        if (!fileUploadService.isSharedSeedFile(imageUrl)) {
            fileUploadService.deleteFileIfSupabase(imageUrl)
        }
    }

    suspend fun uploadDetailImages(
        productId: Int,
        userId: Int,
        images: List<ByteArray>,
        contentTypes: List<String>,
        replaceExisting: Boolean = false
    ): List<String> {
        val uploadedNewFiles = mutableListOf<String>()

        try {
            val oldContent = query {
                val product = verifyProductOwnership(productId, userId, Errors.Commerce.Product.PRODUCT_IMAGE_UPLOAD_PERMISSION_DENIED)

                product.detailContent?.let {
                    if (it.isBlank()) emptyList()
                    else try { Json.decodeFromString<List<DetailContentBlock>>(it) } catch (_: Exception) { emptyList() }
                } ?: emptyList()
            }

            if (images.isEmpty()) {
                throw MinImageRequiredException(Errors.File.MIN_IMAGE_REQUIRED)
            }

            if (images.size != contentTypes.size) {
                throw ImageContentTypeMismatchException(Errors.File.IMAGE_CONTENT_TYPE_MISMATCH)
            }

            val oldImageCount = oldContent.count { it.type == "image" && !it.url.isNullOrBlank() }
            if (oldImageCount + images.size > Constants.Commerce.MAX_PRODUCT_DETAIL_CONTENT_IMAGES) {
                throw ProductImagesCountExceededException(Errors.Commerce.Product.PRODUCT_DETAIL_CONTENT_IMAGES_COUNT_EXCEEDED)
            }

            images.forEach { imageData ->
                if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.PRODUCT)) {
                    throw ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED_INDEX)
                }
            }

            val extensions = contentTypes.map { contentType ->
                imageProcessingService.getFileExtension(contentType)
            }

            val newImageUrls = fileUploadService.uploadProductImages(productId, images, extensions)
            uploadedNewFiles.addAll(newImageUrls)

            // dimensions 저장
            val newImageBlocks = newImageUrls.mapIndexed { index, url ->
                val dimensions = images.getOrNull(index)?.let { bytes ->
                    imageProcessingService.extractImageDimensions(bytes)
                }
                DetailContentBlock(
                    type = "image",
                    url = url,
                    width = dimensions?.first,
                    height = dimensions?.second
                )
            }
            val updatedContent = if (replaceExisting) {
                oldContent.filter { it.type != "image" } + newImageBlocks
            } else {
                // placeholder 순서대로 교체
                val queue = ArrayDeque(newImageBlocks)
                val replaced = oldContent.map { block ->
                    if (block.type == "image" && block.url.isNullOrBlank() && queue.isNotEmpty()) {
                        queue.removeFirst()
                    } else {
                        block
                    }
                }
                replaced + queue.toList()
            }

            val updated = query {
                productRepository.updateDetailContent(productId, Json.encodeToString(updatedContent))
            }

            if (updated == null) {
                rollbackProductImages(uploadedNewFiles)
                throw ProductUpdateFailedException(Errors.Commerce.Product.PRODUCT_UPDATE_FAILED)
            }

            if (replaceExisting) {
                oldContent.filter { it.type == "image" }.mapNotNull { it.url }.forEach { oldUrl ->
                    fileUploadService.deleteFileIfSupabase(oldUrl)
                }
            }

            invalidateProductCaches(productId, userId)

            return newImageUrls

        } catch (e: Exception) {  // 실패 시 업로드 파일 롤백
            rollbackProductImages(uploadedNewFiles)
            throw e
        }
    }

    suspend fun deleteDetailImage(productId: Int, userId: Int, imageUrl: String) {
        query {
            val product = verifyProductOwnership(productId, userId, Errors.Commerce.Product.PRODUCT_IMAGE_DELETE_PERMISSION_DENIED)

            val currentContent: List<DetailContentBlock> = product.detailContent?.let {
                if (it.isBlank()) emptyList()
                else try { Json.decodeFromString(it) } catch (_: Exception) { emptyList() }
            } ?: emptyList()

            val hasImage = currentContent.any { it.type == "image" && it.url == imageUrl }
            if (!hasImage) {
                throw ImageNotFoundException(Errors.File.IMAGE_NOT_FOUND)
            }

            val updatedContent = currentContent.filter { !(it.type == "image" && it.url == imageUrl) }
            productRepository.updateDetailContent(productId, Json.encodeToString(updatedContent))
        }

        invalidateProductCaches(productId, userId)

        // 시드 공용 파일은 DB에서만 제거
        if (!fileUploadService.isSharedSeedFile(imageUrl)) {
            fileUploadService.deleteFileIfSupabase(imageUrl)
        }
    }

    // 판매 상태·타임딜
    suspend fun toggleProductStatus(productId: Int, userId: Int): String {
        val message = query {
            val product = productRepository.findProductById(productId)
                ?: throw ProductNotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

            if (product.creatorId != userId) {
                throw PermissionDeniedException(Errors.Commerce.Product.PRODUCT_STATUS_UPDATE_PERMISSION_DENIED)
            }

            // 해제 계정 판매 재개 방지
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)
            if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
                throw PermissionDeniedException(Errors.Commerce.Product.PRODUCT_PERMISSION_DENIED)
            }

            // 품절 상태는 재고로만 관리되므로 토글 불가
            if (product.status == ProductStatus.SOLD_OUT) {
                throw ProductSoldOutException(Errors.Commerce.Product.PRODUCT_SOLD_OUT_CANNOT_TOGGLE)
            }

            val newStatus = if (product.status == ProductStatus.ACTIVE) {
                ProductStatus.DISCONTINUED
            } else {
                ProductStatus.ACTIVE
            }

            productRepository.updateProductStatus(productId, newStatus)

            if (newStatus == ProductStatus.DISCONTINUED) {
                Messages.Commerce.PRODUCT_DISCONTINUED
            } else {
                Messages.Commerce.PRODUCT_RESUMED
            }
        }

        invalidateProductCaches(productId, userId)

        return message
    }

    suspend fun setProductDeal(productId: Int, userId: Int, request: SetProductDealRequest): ProductResponse {
        val dealPrice = request.dealPrice?.let { BigDecimal(it) }  // status와 독립적으로 동작
        val startAt = request.startAt
        val endAt = request.endAt
        val isClear = dealPrice == null && startAt == null && endAt == null

        val response = query {
            val product = verifyProductOwnership(productId, userId, Errors.Commerce.Product.PRODUCT_DEAL_PERMISSION_DENIED)

            if (!isClear) {
                if (dealPrice == null || startAt == null || endAt == null) {
                    throw InvalidInputException(Errors.Commerce.Product.INVALID_DEAL_FIELDS)
                }
                if (dealPrice <= BigDecimal.ZERO || dealPrice >= product.price) {
                    throw InvalidPriceException(Errors.Commerce.Product.INVALID_DEAL_PRICE)
                }
                if (endAt <= startAt || endAt <= nowUtc()) {
                    throw InvalidInputException(Errors.Commerce.Product.INVALID_DEAL_PERIOD)
                }
            }

            val updated = productRepository.setProductDeal(
                productId = productId,
                dealPrice = if (isClear) null else dealPrice,
                startAt = if (isClear) null else startAt,
                endAt = if (isClear) null else endAt
            ) ?: throw ProductUpdateFailedException(Errors.Commerce.Product.PRODUCT_UPDATE_FAILED)

            val user = userRepository.findUserById(userId)!!
            updated.toProductResponse(user.toSummaryResponse())
        }

        invalidateProductCaches(productId, userId)

        return response
    }

    // 상품 삭제
    suspend fun deleteProduct(productId: Int, userId: Int) {
        val imageUrls = query {
            val product = verifyProductOwnership(productId, userId, Errors.Commerce.Product.PRODUCT_DELETE_PERMISSION_DENIED)

            val urls = product.imageUrls.decodeJsonToList()
            val detailContentImageUrls = extractDetailContentImageUrls(product.detailContent)

            val deleted = productRepository.deleteProduct(productId)
            if (!deleted) {
                throw ProductDeleteFailedException(Errors.Commerce.Product.PRODUCT_DELETE_FAILED)
            }

            // 시드 공용 파일 제외
            (urls + detailContentImageUrls).filterNot { fileUploadService.isSharedSeedFile(it) }
        }

        imageUrls.forEach { url ->
            try {
                fileUploadService.deleteFileIfSupabase(url)
            } catch (e: Exception) {
                logger.warn("상품 이미지 삭제 실패: {} - {}", url, e.message)
            }
        }

        invalidateProductCaches(productId, userId)
    }

    // 인기·섹션·연관 상품
    suspend fun getPopularProducts(limit: Int, currentUserId: Int? = null): List<ProductResponse> {
        val validLimit = when {  // 1~100 보정
            limit < 1 -> 20
            limit > 100 -> 100
            else -> limit
        }

        return query {
            val products = productRepository.findPopularProducts(validLimit)

            val userIds = products.map { it.creatorId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }
            val accessMap = getProductAccessMap(currentUserId, products)

            products.map { product ->
                val creator = userMap[product.creatorId]?.toSummaryResponse()
                    ?: throw CreatorNotFoundException(Errors.Commerce.Product.CREATOR_NOT_FOUND)

                val canAccess = accessMap[product.id.value] ?: false
                product.toProductResponse(creator, canAccess)
            }
        }
    }

    suspend fun getTrendingProducts(limit: Int, currentUserId: Int? = null): List<ProductResponse> {
        val validLimit = when {  // 1~100 보정
            limit < 1 -> 20
            limit > 100 -> 100
            else -> limit
        }

        return query {
            val products = productRepository.findTrendingProducts(validLimit)

            val userIds = products.map { it.creatorId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }
            val accessMap = getProductAccessMap(currentUserId, products)

            products.map { product ->
                val creator = userMap[product.creatorId]?.toSummaryResponse()
                    ?: throw CreatorNotFoundException(Errors.Commerce.Product.CREATOR_NOT_FOUND)

                val canAccess = accessMap[product.id.value] ?: false
                product.toProductResponse(creator, canAccess)
            }
        }
    }

    suspend fun getCreatorStoreSections(
        creatorId: Int,
        currentUserId: Int?,
        page: Int,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: ProductSortType = ProductSortType.LATEST
    ): CreatorStoreResponse {
        val cacheKey = CacheKeys.creatorStore(creatorId, page, limit, currentUserId) + ":$sort"
        cacheService.getJson<CreatorStoreResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val (validPage, validLimit) = validatePaginationParams(page, limit)

            val sectionTags = tagRepository.findSectionEnabledTags(creatorId, TagTargetType.PRODUCT)

            val tagSections = if (sectionTags.isNotEmpty()) {
                // 태그별 최신 상품 기준 정렬
                val tagsWithLatestProduct = sectionTags.mapNotNull { tag ->
                    val productIds = tagRepository.findProductIdsByTag(tag.id.value, page = 1, limit = 1)
                    if (productIds.isNotEmpty()) {
                        val product = productRepository.findProductById(productIds.first())
                        if (product != null) {
                            Triple(tag, product.createdAt, productIds)
                        } else null
                    } else {
                        null
                    }
                }.sortedByDescending { it.second }  // 태그별 최신 상품 기준 정렬

                val topTagSections = tagsWithLatestProduct.take(Constants.Social.MAX_TAG_SECTIONS)

                topTagSections.map { (tag, _, _) ->
                    val productIds = tagRepository.findProductIdsByTag(
                        tagId = tag.id.value,
                        page = 1,
                        limit = Constants.Social.MAX_ITEMS_PER_SECTION
                    )

                    val products = productRepository.findProductsByIds(productIds)
                    val totalCount = tagRepository.countProductsByTag(tag.id.value)

                    val userIds = products.map { it.creatorId }.distinct()
                    val users = userRepository.findUsersByIds(userIds)
                    val userMap = users.associateBy { it.id.value }
                    val accessMap = getProductAccessMap(currentUserId, products)

                    val productResponses = products.mapNotNull { product ->
                        val creator = userMap[product.creatorId]?.toSummaryResponse()
                            ?: return@mapNotNull null

                        val canAccess = accessMap[product.id.value] ?: false
                        product.toProductResponse(creator, canAccess)
                    }

                    ProductTagSectionResponse(
                        tag = tag.toTagResponse(productCount = totalCount),
                        products = productResponses,
                        hasMore = totalCount > Constants.Social.MAX_ITEMS_PER_SECTION
                    )
                }
            } else {
                emptyList()
            }

            val allProducts = productRepository.findProductsByCreator(creatorId, validPage, validLimit, sort)
            val totalCount = productRepository.countProductsByCreator(creatorId)

            val allUserIds = allProducts.map { it.creatorId }.distinct()
            val allUsers = userRepository.findUsersByIds(allUserIds)
            val allUserMap = allUsers.associateBy { it.id.value }
            val allAccessMap = getProductAccessMap(currentUserId, allProducts)

            val allProductResponses = allProducts.mapNotNull { product ->
                val creator = allUserMap[product.creatorId]?.toSummaryResponse()
                    ?: return@mapNotNull null

                val canAccess = allAccessMap[product.id.value] ?: false
                product.toProductResponse(creator, canAccess)
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            val allProductsResponse = createPagedResponse(allProductResponses, pagination)

            CreatorStoreResponse(
                tagSections = tagSections,
                allProducts = allProductsResponse
            )
        }

        cacheService.setJson(cacheKey, response, ttl = 15.minutes)

        return response
    }

    suspend fun getProductsByTag(
        tagId: Int,
        currentUserId: Int?,
        page: Int,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): ProductListResponse {
        return query {
            val (validPage, validLimit) = validatePaginationParams(page, limit)

            val productIds = tagRepository.findProductIdsByTag(tagId, validPage, validLimit)
            val totalCount = tagRepository.countProductIdsByTag(tagId)

            val products = if (productIds.isNotEmpty()) {
                productRepository.findProductsByIds(productIds)
            } else {
                emptyList()
            }

            val userIds = products.map { it.creatorId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }
            val accessMap = getProductAccessMap(currentUserId, products)

            val productResponses = products.mapNotNull { product ->
                val creator = userMap[product.creatorId]?.toSummaryResponse()
                    ?: return@mapNotNull null

                val canAccess = accessMap[product.id.value] ?: false
                product.toProductResponse(creator, canAccess)
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(productResponses, pagination)
        }
    }

    suspend fun getRelatedProducts(
        productId: Int,
        limit: Int = 10,
        currentUserId: Int? = null
    ): List<ProductResponse> {
        val validLimit = limit.coerceIn(1, 20)

        val cacheKey = CacheKeys.relatedProducts(productId, currentUserId)
        cacheService.getJson<List<ProductResponse>>(cacheKey)?.let {
            return it
        }

        val response = query {
            val product = productRepository.findProductById(productId)
                ?: throw ProductNotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

            // 매칭 수 → 판매량 순
            val tags = tagRepository.findTagsByProduct(productId)
            val tagIds = tags.map { it.id.value }

            val tagRelated = if (tagIds.isNotEmpty()) {
                productRepository.findRelatedProductsByTags(
                    tagIds = tagIds,
                    excludeProductId = productId,
                    excludeCreatorId = product.creatorId,
                    limit = validLimit
                )
            } else {
                emptyList()
            }

            val allRelated = if (tagRelated.size < validLimit && product.categoryId != null) {  // 태그 매칭 부족하면 카테고리로 보충
                val excludeIds = tagRelated.map { it.id.value }.toSet() + productId
                val remaining = validLimit - tagRelated.size

                val categoryRelated = productRepository.findRelatedProductsByCategory(
                    categoryId = product.categoryId!!,
                    excludeProductIds = excludeIds,
                    excludeCreatorId = product.creatorId,
                    limit = remaining
                )

                tagRelated + categoryRelated
            } else {
                tagRelated
            }

            val userIds = allRelated.map { it.creatorId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }
            val accessMap = getProductAccessMap(currentUserId, allRelated)

            allRelated.mapNotNull { p ->
                val creator = userMap[p.creatorId]?.toSummaryResponse() ?: return@mapNotNull null
                val canAccess = accessMap[p.id.value] ?: false
                p.toProductResponse(creator, canAccess)
            }
        }

        cacheService.setJson(cacheKey, response, ttl = 15.minutes)

        return response
    }

    // 접근 판정
    suspend fun getProductAccessMap(
        userId: Int?,
        products: Collection<ProductDao>
    ): Map<Int, Boolean> {
        val productList = products.toList()
        if (productList.isEmpty()) return emptyMap()

        if (userId == null) {  // 비로그인은 FREE만 접근
            return productList.associate { product ->
                product.id.value to (product.requiredTier == SubscriptionPlanTier.FREE)
            }
        }

        val restrictedProducts = productList.filter { product ->
            product.creatorId != userId && product.requiredTier != SubscriptionPlanTier.FREE
        }

        if (restrictedProducts.isEmpty()) {  // 제한 상품 없으면 즉시 true
            return productList.associate { it.id.value to true }
        }

        val creatorIds = restrictedProducts.map { it.creatorId }.distinct()
        val now = nowUtc()

        val subscriptions = subscriptionRepository.findActiveSubscriptionsByCreators(userId, creatorIds)
            .filter { it.expiresAt > now }
        val subscriptionMap = subscriptions.associateBy { it.creatorId }

        val planIds = subscriptions.map { it.planId }.distinct()
        val planMap = planRepository.findPlansByIds(planIds).associateBy { it.id.value }

        val creatorTierMap = subscriptionMap.mapNotNull { (creatorId, subscription) ->
            val tier = planMap[subscription.planId]?.tier ?: return@mapNotNull null
            creatorId to tier
        }.toMap()

        return productList.associate { product ->
            product.id.value to resolveProductAccess(userId, product, creatorTierMap)
        }
    }

    private suspend fun checkProductAccess(userId: Int?, product: ProductDao): Boolean {
        if (userId == null) {  // 비로그인은 FREE만
            return resolveProductAccess(
                userId = null,
                product = product,
                creatorTierMap = emptyMap()
            )
        }

        val creatorTierMap = if (product.creatorId == userId || product.requiredTier == SubscriptionPlanTier.FREE) {  // 본인 상품·FREE는 항상 허용
            emptyMap()
        } else {
            val subscription = subscriptionRepository.findActiveSubscription(userId, product.creatorId)
                ?: return false
            val plan = planRepository.findPlanById(subscription.planId)
                ?: return false
            mapOf(product.creatorId to plan.tier)
        }

        return resolveProductAccess(userId, product, creatorTierMap)
    }

    private fun resolveProductAccess(
        userId: Int?,
        product: ProductDao,
        creatorTierMap: Map<Int, SubscriptionPlanTier>
    ): Boolean {
        return TierAccessEvaluator.canAccess(
            viewerId = userId,
            ownerId = product.creatorId,
            creatorId = product.creatorId,
            requiredTier = product.requiredTier,
            isSecret = false,
            viewerTier = creatorTierMap[product.creatorId]
        )
    }

    // 공통 보조
    private suspend fun verifyProductOwnership(productId: Int, userId: Int, errorMessage: String): ProductDao {
        val product = productRepository.findProductById(productId)
            ?: throw ProductNotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

        if (!productRepository.isProductOwnedBy(productId, userId)) {
            throw PermissionDeniedException(errorMessage)
        }

        return product
    }

    private suspend fun invalidateProductCaches(productId: Int, userId: Int) {
        cacheService.deletePattern(CacheKeys.Patterns.product(productId))
        cacheService.deletePattern(CacheKeys.Patterns.creatorStore(userId))
        cacheService.deletePattern(CacheKeys.Patterns.relatedProducts(productId))
    }

    private suspend fun rollbackProductImages(uploadedUrls: List<String>) {
        if (uploadedUrls.isEmpty()) return

        uploadedUrls.forEach { url ->
            try {
                fileUploadService.deleteFileIfSupabase(url)
            } catch (e: Exception) {
                logger.warn("상품 이미지 롤백 실패: {} - {}", url, e.message)
            }
        }
    }
}
