package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.subscription.SubscriptionDao
import com.ninezero.core.database.entities.subscription.SubscriptionPlanDao
import com.ninezero.features.commerce.data.CartRepository
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.data.WishlistRepository
import com.ninezero.features.commerce.effectiveOriginalPrice
import com.ninezero.features.commerce.effectivePrice
import com.ninezero.features.commerce.presentation.models.response.WishlistListResponse
import com.ninezero.features.commerce.presentation.models.response.WishlistProductResponse
import com.ninezero.features.commerce.presentation.models.response.WishlistToggleResponse
import com.ninezero.features.commerce.toWishlistResponse
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.toSummaryResponse
import java.math.BigDecimal
import java.math.RoundingMode

class WishlistService(
    private val wishlistRepository: WishlistRepository,
    private val productRepository: ProductRepository,
    private val cartRepository: CartRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository
) {

    suspend fun toggleWishlist(userId: Int, productId: Int): WishlistToggleResponse {
        val result = query {
            wishlistRepository.toggleWishlist(userId, productId)
        }

        val message = if (result.isWishlisted) {
            Messages.Commerce.WISHLIST_ADDED
        } else {
            Messages.Commerce.WISHLIST_REMOVED
        }

        return WishlistToggleResponse(
            isWishlisted = result.isWishlisted,
            wishlistCount = result.wishlistCount,
            message = message
        )
    }

    suspend fun getWishlist(
        userId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): WishlistListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        val (wishlistResponses, totalCount) = query {
            val wishlists = wishlistRepository.findWishlistWithProducts(
                userId,
                validPage,
                validLimit
            )
            val count = wishlistRepository.countWishlist(userId)
            val accessMap = buildProductAccessMap(userId, wishlists.map { it.product })

            val responses = wishlists.map { wishlistWithProduct ->
                val creatorSummary = wishlistWithProduct.creator.toSummaryResponse()
                val product = wishlistWithProduct.product

                // 타임딜 유효가 기준 할인율 계산
                val effPrice = product.effectivePrice()
                val effOriginal = product.effectiveOriginalPrice()
                val discountRate = if (effOriginal != null && effOriginal > BigDecimal.ZERO) {
                    ((effOriginal - effPrice) / effOriginal * BigDecimal(100))
                        .setScale(0, RoundingMode.HALF_UP)
                        .toInt()
                } else {
                    null
                }

                val canAccess = accessMap[product.id.value] ?: false
                val purchaseState = resolvePurchaseState(product, canAccess)

                val wishlistProductResponse = WishlistProductResponse(
                    id = product.id.value,
                    name = product.name,
                    description = product.description,
                    price = effPrice.toAmountString(),
                    originalPrice = effOriginal?.toAmountString(),
                    discountRate = discountRate,
                    imageUrl = product.imageUrls.decodeJsonToList().firstOrNull(),
                    stock = product.stock,
                    status = product.status,
                    isActive = product.isActive,
                    canAccess = canAccess,
                    purchaseState = purchaseState,
                    rating = product.rating.setScale(1, RoundingMode.HALF_UP).toString(),
                    reviewCount = product.reviewCount,
                    creator = creatorSummary
                )

                wishlistWithProduct.wishlist.toWishlistResponse(wishlistProductResponse)
            }

            Pair(responses, count)
        }

        val pagination = PaginationInfo(validPage, validLimit, totalCount)
        return createPagedResponse(wishlistResponses, pagination)
    }

    suspend fun checkWishlistStatus(userId: Int, productId: Int): Map<String, Boolean> {
        val isWishlisted = query {
            wishlistRepository.isInWishlist(userId, productId)
        }
        return mapOf("isWishlisted" to isWishlisted)
    }

    suspend fun getWishlistCount(userId: Int): Map<String, Int> {
        val count = query {
            wishlistRepository.countWishlist(userId)
        }
        return mapOf("count" to count)
    }

    suspend fun getWishlistProductIds(
        userId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): Map<String, List<Int>> {
        val (validPage, validLimit) = validatePaginationParams(page, limit)
        val productIds = query {
            wishlistRepository.findWishlistProductIds(userId, validPage, validLimit)
        }
        return mapOf("wishlists" to productIds)
    }

    suspend fun moveToCart(userId: Int, productId: Int): String {
        query {
            val product = productRepository.findProductById(productId)
                ?: throw NotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

            if (!wishlistRepository.isInWishlist(userId, productId)) {
                throw NotFoundException(Errors.Commerce.Wishlist.WISHLIST_ITEM_NOT_FOUND)
            }

            val canAccess = canAccessProduct(userId, product)
            when (resolvePurchaseState(product, canAccess)) {
                PurchaseState.NO_ACCESS ->
                    throw PermissionDeniedException(Errors.Commerce.Product.PRODUCT_ACCESS_DENIED)
                PurchaseState.DISCONTINUED ->
                    throw ProductInactiveException(Errors.Commerce.Product.PRODUCT_DISCONTINUED)
                PurchaseState.SOLD_OUT ->
                    throw ProductSoldOutException(Errors.Commerce.Product.PRODUCT_SOLD_OUT)
                PurchaseState.PURCHASABLE -> Unit
            }

            cartRepository.createCartItem(userId, productId, 1)

            val deleted = wishlistRepository.deleteWishlist(userId, productId)
            if (!deleted) {
                throw NotFoundException(Errors.Commerce.Wishlist.WISHLIST_ITEM_NOT_FOUND)
            }

            productRepository.decrementLikeCount(productId)
        }

        return Messages.Commerce.WISHLIST_MOVED_TO_CART
    }

    private suspend fun buildProductAccessMap(userId: Int, products: List<ProductDao>): Map<Int, Boolean> {
        if (products.isEmpty()) return emptyMap()

        val creatorIds = products.map { it.creatorId }.distinct()
        val subscriptions = subscriptionRepository.findActiveSubscriptionsByCreators(userId, creatorIds)
        val subscriptionMap = subscriptions.associateBy { it.creatorId }

        val planIds = subscriptions.map { it.planId }.distinct()
        val planMap = planRepository.findPlansByIds(planIds).associateBy { it.id.value }

        return products.associate { product ->
            product.id.value to canAccessProduct(userId, product, subscriptionMap, planMap)
        }
    }

    private fun canAccessProduct(
        userId: Int,
        product: ProductDao,
        subscriptionMap: Map<Int, SubscriptionDao>,
        planMap: Map<Int, SubscriptionPlanDao>
    ): Boolean {
        val viewerTier = subscriptionMap[product.creatorId]?.let { planMap[it.planId]?.tier }
        return TierAccessEvaluator.canAccess(
            viewerId = userId,
            ownerId = product.creatorId,
            creatorId = product.creatorId,
            requiredTier = product.requiredTier,
            isSecret = false,
            viewerTier = viewerTier
        )
    }

    private suspend fun canAccessProduct(userId: Int, product: ProductDao): Boolean {
        // 티어 잠금 상품만 구독 조회 후 판정 위임
        val viewerTier = if (product.creatorId != userId && product.requiredTier != SubscriptionPlanTier.FREE) {
            subscriptionRepository.findActiveSubscription(userId, product.creatorId)
                ?.let { planRepository.findPlanById(it.planId)?.tier }
        } else null

        return TierAccessEvaluator.canAccess(
            viewerId = userId,
            ownerId = product.creatorId,
            creatorId = product.creatorId,
            requiredTier = product.requiredTier,
            isSecret = false,
            viewerTier = viewerTier
        )
    }

    private fun resolvePurchaseState(product: ProductDao, canAccess: Boolean): PurchaseState {
        return when {
            !canAccess -> PurchaseState.NO_ACCESS
            !product.isActive || product.status == ProductStatus.DISCONTINUED -> PurchaseState.DISCONTINUED
            product.stock <= 0 || product.status == ProductStatus.SOLD_OUT -> PurchaseState.SOLD_OUT
            else -> PurchaseState.PURCHASABLE
        }
    }
}
