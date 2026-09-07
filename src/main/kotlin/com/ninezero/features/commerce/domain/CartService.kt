package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.TierAccessEvaluator
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.features.commerce.data.CartRepository
import com.ninezero.features.commerce.data.CartRow
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.presentation.models.request.CartRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateCartRequest
import com.ninezero.features.commerce.presentation.models.response.CartItemResponse
import com.ninezero.features.commerce.presentation.models.response.CartResponse
import com.ninezero.features.commerce.toCartItemResponse
import com.ninezero.features.commerce.toProductResponse
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.toSummaryResponse
import java.math.BigDecimal

class CartService(
    private val cartRepository: CartRepository,
    private val productRepository: ProductRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository
) {

    // 장바구니 담기/수정/삭제
    suspend fun addToCart(userId: Int, request: CartRequest): CartResponse {
        ValidationUtils.validateCartQuantity(request.quantity)

        val cartItemResponses = query {
            val existingQuantity = cartRepository.findCartItem(userId, request.productId)?.quantity ?: 0
            validatePurchasable(userId, request.productId, existingQuantity + request.quantity)  // 기존 수량과 합산해 검증

            cartRepository.createCartItem(userId, request.productId, request.quantity)

            val cartsWithProducts = cartRepository.findUserCartWithProducts(userId)
            mapCartItemResponses(userId, cartsWithProducts)
        }

        return CartResponse(
            items = cartItemResponses,
            totalItems = cartItemResponses.size,
            totalPrice = calcTotalPrice(cartItemResponses).toString()
        )
    }

    suspend fun getUserCart(userId: Int): CartResponse {
        val cartItemResponses = query {
            val cartsWithProducts = cartRepository.findUserCartWithProducts(userId)
            mapCartItemResponses(userId, cartsWithProducts)
        }

        return CartResponse(
            items = cartItemResponses,
            totalItems = cartItemResponses.size,
            totalPrice = calcTotalPrice(cartItemResponses).toString()
        )
    }

    suspend fun updateCartQuantity(cartId: Int, userId: Int, request: UpdateCartRequest): CartResponse {
        ValidationUtils.validateCartQuantity(request.quantity)

        val cartItemResponses = query {
            val cart = cartRepository.findCartById(cartId)
                ?: throw NotFoundException(Errors.Commerce.Cart.CART_ITEM_NOT_FOUND)

            if (!cartRepository.isCartOwnedBy(cartId, userId)) {
                throw ForbiddenException(Errors.Commerce.Cart.CART_UPDATE_PERMISSION_DENIED)
            }

            validatePurchasable(userId, cart.productId, request.quantity)  // 변경 수량 기준 재확인

            cartRepository.updateCartItemQuantity(cartId, request.quantity)

            val cartsWithProducts = cartRepository.findUserCartWithProducts(userId)
            mapCartItemResponses(userId, cartsWithProducts)
        }

        return CartResponse(
            items = cartItemResponses,
            totalItems = cartItemResponses.size,
            totalPrice = calcTotalPrice(cartItemResponses).toString()
        )
    }

    suspend fun removeFromCart(cartId: Int, userId: Int) {
        query {
            if (!cartRepository.isCartOwnedBy(cartId, userId)) {
                throw ForbiddenException(Errors.Commerce.Cart.CART_DELETE_PERMISSION_DENIED)
            }

            val removed = cartRepository.deleteCartItem(cartId)
            if (!removed) {
                throw NotFoundException(Errors.Commerce.Cart.CART_DELETE_FAILED)
            }
        }
    }

    suspend fun clearCart(userId: Int) {
        query { cartRepository.clearUserCart(userId) }
    }

    suspend fun getCartItemCount(userId: Int): Map<String, Int> {
        return query {
            mapOf("count" to cartRepository.countCartItems(userId))
        }
    }

    // 구매 가능 검증
    suspend fun validatePurchasable(
        userId: Int,
        productId: Int,
        requestedQuantity: Int
    ): ProductDao {
        val product = productRepository.findProductById(productId)
            ?: throw NotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

        validatePurchasable(userId, product, requestedQuantity)
        return product
    }

    suspend fun validatePurchasable(
        userId: Int,
        product: ProductDao,
        requestedQuantity: Int
    ) {
        if (!product.isActive) {
            throw ProductInactiveException(Errors.Commerce.Product.PRODUCT_DISCONTINUED)
        }

        when (product.status) {
            ProductStatus.DISCONTINUED -> throw ProductInactiveException(Errors.Commerce.Product.PRODUCT_DISCONTINUED)
            ProductStatus.SOLD_OUT -> throw ProductSoldOutException(Errors.Commerce.Product.PRODUCT_SOLD_OUT)
            ProductStatus.ACTIVE -> Unit
        }

        if (product.creatorId == userId) {
            throw InvalidInputException(Errors.Commerce.Cart.CANNOT_BUY_OWN_PRODUCT)
        }

        val canAccess = canAccessProduct(userId, product)
        if (!canAccess) {
            throw SubscriptionRequiredException(Errors.Commerce.Product.PRODUCT_ACCESS_DENIED)
        }

        ValidationUtils.validateStockAvailability(product.stock, requestedQuantity)
    }

    // 공통 보조
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

    private suspend fun mapCartItemResponses(
        userId: Int,
        cartsWithProducts: List<CartRow>
    ): List<CartItemResponse> {
        if (cartsWithProducts.isEmpty()) return emptyList()

        val creatorIds = cartsWithProducts
            .map { it.product.creatorId }
            .filter { it != userId }
            .distinct()

        val creatorTierMap = if (creatorIds.isEmpty()) {
            emptyMap()
        } else {
            val now = nowUtc()
            val subscriptions = subscriptionRepository.findActiveSubscriptionsByCreators(userId, creatorIds)
                .filter { it.expiresAt > now }
            val planMap = planRepository.findPlansByIds(subscriptions.map { it.planId }.distinct())
                .associateBy { it.id.value }

            subscriptions.mapNotNull { subscription ->
                val tier = planMap[subscription.planId]?.tier ?: return@mapNotNull null
                subscription.creatorId to tier
            }.toMap()
        }

        return cartsWithProducts.map { cartWithProduct ->
            val creatorSummary = cartWithProduct.creator.toSummaryResponse()
            val canAccess = resolveProductAccess(
                userId = userId,
                product = cartWithProduct.product,
                creatorTierMap = creatorTierMap
            )
            val productResponse = cartWithProduct.product.toProductResponse(creatorSummary, canAccess = canAccess)
            cartWithProduct.cart.toCartItemResponse(productResponse)
        }
    }

    private fun resolveProductAccess(
        userId: Int,
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

    private fun calcTotalPrice(items: List<CartItemResponse>): BigDecimal {
        return items.fold(BigDecimal.ZERO) { acc, item ->
            acc + BigDecimal(item.subtotal)
        }
    }
}
