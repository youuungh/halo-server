package com.ninezero.features.commerce.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.extractDetailContentImageUrls
import com.ninezero.features.commerce.normalizeProductImages
import com.ninezero.features.commerce.presentation.models.DetailContentBlock
import com.ninezero.features.commerce.presentation.models.request.ProductImageRef
import com.ninezero.features.commerce.presentation.models.request.ProductRequest
import com.ninezero.features.commerce.presentation.models.request.ProductSearchRequest
import com.ninezero.features.commerce.presentation.models.request.SetProductDealRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateProductRequest
import com.ninezero.features.commerce.presentation.models.response.CreatorStoreResponse
import com.ninezero.features.commerce.presentation.models.response.ProductListResponse
import com.ninezero.features.commerce.presentation.models.response.ProductResponse
import com.ninezero.features.commerce.presentation.models.response.ProductTagSectionResponse
import com.ninezero.features.commerce.toDisplayName
import com.ninezero.features.commerce.toProductResponse
import com.ninezero.features.commerce.withCurrentDeal
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.tag.data.TagRepository
import com.ninezero.features.tag.domain.requireOwnedTags
import com.ninezero.features.tag.toTagResponse
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toUserSummaryResponse
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
    private val mediaDraftService: MediaDraftService,
    private val fileUploadService: FileUploadService,
    private val cacheService: CacheService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    // 상품 생성
    suspend fun createProduct(userId: Int, request: ProductRequest): ProductResponse {
        val name = request.name.trim()
        val description = request.description.trim()
        val brandName = request.brandName?.trim()

        ValidationUtils.validateProductName(name)
        ValidationUtils.validateProductDescription(description)

        val price = BigDecimal(request.price)
        ValidationUtils.validatePositivePrice(price)
        ValidationUtils.validateMaxPrice(price)

        val originalPrice = request.originalPrice?.let { BigDecimal(it) }
        ValidationUtils.validateOriginalPrice(originalPrice, price)
        originalPrice?.let { ValidationUtils.validateMaxPrice(it) }
        ValidationUtils.validateStock(request.stock)

        ValidationUtils.validateProductImageCount(request.imageDraftIds.size)

        val tags = request.tags ?: emptyList()
        ValidationUtils.validateProductTags(tags)

        request.tagIds?.let { tagIds ->
            if (request.sectionTagId != null && request.sectionTagId !in tagIds) {
                throw InvalidTagException(Errors.Tag.SECTION_TAG_NOT_IN_SELECTED_TAGS)
            }
        }

        val (response, productId) = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
                throw ForbiddenException(Errors.Commerce.Product.PRODUCT_REGISTRATION_PERMISSION_DENIED)
            }

            // 유료 tier는 해당 tier 활성 플랜 필요, ADMIN은 면제
            if (request.requiredTier != SubscriptionPlanTier.FREE && user.role == UserRole.CREATOR) {
                if (!planRepository.existsActivePlanForTier(userId, request.requiredTier)) {
                    throw SubscriptionPlanRequiredException(
                        Errors.Commerce.Product.PLAN_CREATE_REQUIRED.format(request.requiredTier.toDisplayName())
                    )
                }
            }

            // 초안을 거두어 이미지 주소로 채우고 초안은 지움
            val imageUrls = mediaDraftService.claimInTransaction(userId, MediaDraftTargetType.PRODUCT, request.imageDraftIds).map { it.url }
            val detailContent = request.detailContent?.let { resolveDetailImages(userId, it, storedUrls = emptySet()) }

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
                detailContent = detailContent?.let { Json.encodeToString(it) },
                tags = tags,
                requiredTier = request.requiredTier
            )

            request.tagIds?.let { tagIds ->
                requireOwnedTags(tagRepository, userId, tagIds, TagTargetType.PRODUCT)
                tagRepository.attachTagsToProduct(
                    productId = createdProduct.id.value,
                    tagIds = tagIds,
                    sectionTagId = request.sectionTagId
                )
            }

            val creatorSummary = user.toUserSummaryResponse()
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
        cacheService.getJson<ProductResponse>(cacheKey)?.let { cached ->
            // 캐시는 작성자·접근권만 쓰고 상품·섹션 태그는 새로 조회
            val refreshed = query {
                productRepository.findProductById(productId)?.toProductResponse(
                    cached.creator,
                    canAccess = cached.canAccess,
                    sectionTag = findSectionTag(productId)
                )
            }
            if (refreshed != null) return refreshed
        }

        val response = query {
            // 삭제 상품은 404
            val product = productRepository.findProductById(productId)
                ?: throw ProductNotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

            val creator = userRepository.findUserById(product.creatorId)?.toUserSummaryResponse()
                ?: throw CreatorNotFoundException(Errors.Commerce.Product.CREATOR_NOT_FOUND)

            // 잠금 상품도 미리보기 반환
            val hasAccess = checkProductAccess(currentUserId, product)

            productRepository.incrementViewCount(productId)

            // 수정 화면이 이 값으로 섹션 태그를 채움
            product.toProductResponse(creator, canAccess = hasAccess, sectionTag = findSectionTag(productId))
        }

        cacheService.setJson(cacheKey, response, ttl = 30.minutes)

        return response
    }

    private suspend fun findSectionTag(productId: Int) =
        tagRepository.findSectionTagsByProducts(listOf(productId))[productId]?.toTagResponse()

    suspend fun getAllProducts(
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        currentUserId: Int? = null
    ): ProductListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val products = productRepository.findAllProducts(validPage, validLimit)
            val total = productRepository.countAllProducts()

            val userIds = products.map { it.creatorId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }
            // 접근권한 일괄 조회
            val accessMap = getProductAccessMap(currentUserId, products)

            val productResponses = products.map { product ->
                val creator = userMap[product.creatorId]?.toUserSummaryResponse()
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
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): CursorResponse<ProductResponse> {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)

            if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
                throw ForbiddenException(Errors.Commerce.Product.PRODUCT_PERMISSION_DENIED)
            }

            val (products, nextCursor) = productRepository.findMyProducts(userId, pageCursor, validLimit + 1)
                .toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }
            val creator = user.toUserSummaryResponse()

            // 섹션 태그 일괄 조회
            val sectionTagMap = tagRepository.findSectionTagsByProducts(products.map { it.id.value })
            val productResponses = products.map { product ->
                val sectionTag = sectionTagMap[product.id.value]?.toTagResponse()
                product.toProductResponse(creator, sectionTag = sectionTag)
            }

            CursorResponse(productResponses, nextCursor, hasNext = nextCursor != null)
        }
    }

    suspend fun searchProducts(request: ProductSearchRequest, currentUserId: Int? = null): ProductListResponse {
        val (validPage, validLimit) = validatePaginationParams(request.page, request.limit)

        return query {
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
                val creator = userMap[product.creatorId]?.toUserSummaryResponse()
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
        val name = request.name?.trim()
        val description = request.description?.trim()
        val brandName = request.brandName?.trim()

        name?.let { ValidationUtils.validateProductName(it) }
        description?.let { ValidationUtils.validateProductDescription(it) }

        val price = request.price?.let { BigDecimal(it) }
        // 정가는 null이면 유지, 빈 값이면 지움
        val clearOriginalPrice = request.originalPrice?.isBlank() == true
        val originalPrice = request.originalPrice?.takeIf { it.isNotBlank() }?.let { BigDecimal(it) }

        price?.let {
            ValidationUtils.validatePositivePrice(it)
            ValidationUtils.validateMaxPrice(it)
        }
        originalPrice?.let { ValidationUtils.validateMaxPrice(it) }

        request.stock?.let { ValidationUtils.validateStock(it) }

        ValidationUtils.validateProductTags(request.tags)

        if (request.status != null) {  // status는 별도 API로만 변경
            throw InvalidInputException(Errors.Commerce.Product.PRODUCT_STATUS_UPDATE_VIA_STATUS_API_ONLY)
        }

        request.tagIds?.let { tagIds ->
            if (request.sectionTagId != null && request.sectionTagId !in tagIds) {
                throw InvalidTagException(Errors.Tag.SECTION_TAG_NOT_IN_SELECTED_TAGS)
            }
        }

        val (response, removedImages) = query {
            val product = verifyProductOwnership(productId, userId, Errors.Commerce.Product.PRODUCT_UPDATE_PERMISSION_DENIED)

            // 한쪽만 바꿔도 저장값과 합쳐 정가 검증
            if (price != null || request.originalPrice != null) {
                val finalOriginalPrice = if (clearOriginalPrice) null else originalPrice ?: product.originalPrice
                ValidationUtils.validateOriginalPrice(finalOriginalPrice, price ?: product.price)
            }

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
            val storedUrls = (oldImageUrls + oldDetailUrls).toSet()

            // 저장된 주소는 유지·재배치, 새 이미지는 초안을 거둠
            val imageUrls = request.images?.let { resolveImageRefs(userId, it, storedUrls) }
            val detailContentJson = request.detailContent?.let { Json.encodeToString(resolveDetailImages(userId, it, storedUrls)) }
            val finalImageUrls = imageUrls ?: oldImageUrls
            val finalDetailUrls = detailContentJson?.let { extractDetailContentImageUrls(it) } ?: oldDetailUrls

            val removed = ((oldImageUrls - finalImageUrls.toSet()) + (oldDetailUrls - finalDetailUrls.toSet()))
                .filterNot { it in finalImageUrls || it in finalDetailUrls }

            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            request.requiredTier?.let { newTier ->
                // 유료 tier는 해당 tier 활성 플랜 필요, ADMIN은 면제
                if (newTier != SubscriptionPlanTier.FREE && user.role == UserRole.CREATOR) {
                    if (!planRepository.existsActivePlanForTier(userId, newTier)) {
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
                requiredTier = request.requiredTier,
                clearOriginalPrice = clearOriginalPrice
            ) ?: throw ProductUpdateFailedException(Errors.Commerce.Product.PRODUCT_UPDATE_FAILED)

            request.tagIds?.let { tagIds ->
                requireOwnedTags(tagRepository, userId, tagIds, TagTargetType.PRODUCT)
                tagRepository.attachTagsToProduct(
                    productId = productId,
                    tagIds = tagIds,
                    sectionTagId = request.sectionTagId
                )
            }

            val creatorSummary = user.toUserSummaryResponse()
            // 내 상품 목록 항목과 같게 섹션 태그 포함
            val sectionTag = tagRepository.findSectionTagsByProducts(listOf(productId))[productId]?.toTagResponse()

            updated.toProductResponse(creatorSummary, sectionTag = sectionTag) to removed
        }

        // 교체로 빠진 이미지 파일 삭제
        fileUploadService.deleteByUrls(removedImages)

        invalidateProductCaches(productId, userId)

        return response
    }

    // 판매 상태·타임딜
    suspend fun toggleProductStatus(productId: Int, userId: Int): String {
        val message = query {
            val product = verifyProductOwnership(productId, userId, Errors.Commerce.Product.PRODUCT_STATUS_UPDATE_PERMISSION_DENIED)

            // 해제 계정 판매 재개 방지
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)
            if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
                throw ForbiddenException(Errors.Commerce.Product.PRODUCT_PERMISSION_DENIED)
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
                dealPrice = dealPrice,
                startAt = startAt,
                endAt = endAt
            ) ?: throw ProductUpdateFailedException(Errors.Commerce.Product.PRODUCT_UPDATE_FAILED)

            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)
            updated.toProductResponse(user.toUserSummaryResponse())
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

            urls + detailContentImageUrls
        }

        fileUploadService.deleteByUrls(imageUrls)

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
                val creator = userMap[product.creatorId]?.toUserSummaryResponse()
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
                val creator = userMap[product.creatorId]?.toUserSummaryResponse()
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
        val (validPage, validLimit) = validatePaginationParams(page, limit)
        val cacheKey = CacheKeys.creatorStore(creatorId, validPage, validLimit, sort.name, currentUserId)
        cacheService.getJson<CreatorStoreResponse>(cacheKey)?.let { cached ->
            // 캐시 중 바뀐 딜 가격만 다시 계산
            val now = nowUtc()
            return cached.copy(
                tagSections = cached.tagSections.map { section ->
                    section.copy(products = section.products.map { it.withCurrentDeal(now) })
                },
                allProducts = cached.allProducts.copy(items = cached.allProducts.items.map { it.withCurrentDeal(now) })
            )
        }

        val response = query {

            val sectionTags = tagRepository.findSectionEnabledTags(creatorId, TagTargetType.PRODUCT)

            val tagSections = if (sectionTags.isNotEmpty()) {
                // 태그별 최신 상품 기준 정렬
                val tagsWithLatestProduct = sectionTags.mapNotNull { tag ->
                    val productIds = tagRepository.findSectionProductIds(tag.id.value, page = 1, limit = 1)
                    productIds.firstOrNull()
                        ?.let { productRepository.findProductById(it) }
                        ?.let { tag to it.createdAt }
                }.sortedByDescending { it.second }  // 태그별 최신 상품 기준 정렬

                val topTagSections = tagsWithLatestProduct.take(Constants.Social.MAX_TAG_SECTIONS)

                topTagSections.map { (tag, _) ->
                    val productIds = tagRepository.findSectionProductIds(
                        tagId = tag.id.value,
                        page = 1,
                        limit = Constants.Social.MAX_ITEMS_PER_SECTION
                    )

                    val products = productRepository.findProductsByIds(productIds)
                    val totalCount = tagRepository.countSectionProducts(tag.id.value)

                    val userIds = products.map { it.creatorId }.distinct()
                    val users = userRepository.findUsersByIds(userIds)
                    val userMap = users.associateBy { it.id.value }
                    val accessMap = getProductAccessMap(currentUserId, products)

                    val productResponses = products.mapNotNull { product ->
                        val creator = userMap[product.creatorId]?.toUserSummaryResponse()
                            ?: return@mapNotNull null

                        val canAccess = accessMap[product.id.value] ?: false
                        product.toProductResponse(creator, canAccess)
                    }

                    ProductTagSectionResponse(
                        tag = tag.toTagResponse(count = totalCount),
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
                val creator = allUserMap[product.creatorId]?.toUserSummaryResponse()
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
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val productIds = tagRepository.findSectionProductIds(tagId, validPage, validLimit)
            val totalCount = tagRepository.countSectionProducts(tagId)

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
                val creator = userMap[product.creatorId]?.toUserSummaryResponse()
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
        cacheService.getJson<List<ProductResponse>>(cacheKey)?.let { cached ->
            // 캐시 중 바뀐 딜 가격만 다시 계산
            val now = nowUtc()
            return cached.map { it.withCurrentDeal(now) }
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
                val creator = userMap[p.creatorId]?.toUserSummaryResponse() ?: return@mapNotNull null
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
        if (products.isEmpty()) return emptyMap()

        if (userId == null) {  // 비로그인은 FREE만 접근
            return products.associate { product ->
                product.id.value to (product.requiredTier == SubscriptionPlanTier.FREE)
            }
        }

        val restrictedProducts = products.filter { product ->
            product.creatorId != userId && product.requiredTier != SubscriptionPlanTier.FREE
        }

        if (restrictedProducts.isEmpty()) {  // 제한 상품 없으면 즉시 true
            return products.associate { it.id.value to true }
        }

        val creatorIds = restrictedProducts.map { it.creatorId }.distinct()

        val subscriptions = subscriptionRepository.findActiveSubscriptionsByCreators(userId, creatorIds)
        val subscriptionMap = subscriptions.associateBy { it.creatorId }

        val planIds = subscriptions.map { it.planId }.distinct()
        val planMap = planRepository.findPlansByIds(planIds).associateBy { it.id.value }

        val creatorTierMap = subscriptionMap.mapNotNull { (creatorId, subscription) ->
            val tier = planMap[subscription.planId]?.tier ?: return@mapNotNull null
            creatorId to tier
        }.toMap()

        return products.associate { product ->
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

        if (product.creatorId != userId) {
            throw ForbiddenException(errorMessage)
        }

        return product
    }

    private suspend fun invalidateProductCaches(productId: Int, userId: Int) {
        cacheService.deletePattern(CacheKeys.Patterns.product(productId))
        cacheService.deletePattern(CacheKeys.Patterns.creatorStore(userId))
        cacheService.deletePattern(CacheKeys.Patterns.relatedProducts(productId))
    }

    /** 대표 이미지 목록을 채움, 저장된 주소는 유지하고 초안은 거둠 */
    private suspend fun resolveImageRefs(userId: Int, refs: List<ProductImageRef>, storedUrls: Set<String>): List<String> {
        val sources = refs.map { it.url?.takeIf(String::isNotBlank) to it.draftId }
        if (sources.any { (url, draftId) -> (url == null) == (draftId == null) }) {  // 둘 중 하나만
            throw InvalidInputException(Errors.Commerce.Product.PRODUCT_IMAGE_SOURCE_REQUIRED)
        }
        if (sources.any { (url, _) -> url != null && url !in storedUrls }) {
            throw InvalidInputException(Errors.File.FILE_NOT_OWNED)
        }
        ValidationUtils.validateProductImageCount(sources.size)

        val drafts = ArrayDeque(mediaDraftService.claimInTransaction(userId, MediaDraftTargetType.PRODUCT, sources.mapNotNull { (_, draftId) -> draftId }))
        return sources.map { (url, _) -> url ?: drafts.removeFirst().url }.normalizeProductImages()
    }

    /** 상세 본문의 이미지 블록을 채움, 저장된 주소는 유지하고 초안은 거둠 */
    private suspend fun resolveDetailImages(
        userId: Int,
        blocks: List<DetailContentBlock>,
        storedUrls: Set<String>
    ): List<DetailContentBlock> {
        val imageBlocks = blocks.filter { it.type == "image" }
        imageBlocks.forEach { block ->
            val url = block.url?.takeIf(String::isNotBlank)
            if ((url == null) == (block.draftId == null)) {  // 둘 중 하나만
                throw InvalidInputException(Errors.Commerce.Product.PRODUCT_IMAGE_SOURCE_REQUIRED)
            }
            if (url != null && url !in storedUrls) {
                throw InvalidInputException(Errors.File.FILE_NOT_OWNED)
            }
        }
        if (imageBlocks.size > Constants.Commerce.MAX_PRODUCT_DETAIL_CONTENT_IMAGES) {
            throw InvalidInputException(Errors.Commerce.Product.PRODUCT_DETAIL_CONTENT_IMAGES_COUNT_EXCEEDED)
        }

        val drafts = ArrayDeque(mediaDraftService.claimInTransaction(userId, MediaDraftTargetType.PRODUCT, imageBlocks.mapNotNull { it.draftId }))
        return blocks.map { block ->
            if (block.type == "image" && block.draftId != null) {
                val draft = drafts.removeFirst()
                block.copy(url = draft.url, width = draft.width, height = draft.height, draftId = null)
            } else {
                block
            }
        }
    }
}
