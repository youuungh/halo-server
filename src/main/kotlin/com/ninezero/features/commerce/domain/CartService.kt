package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import com.ninezero.features.commerce.data.CartRepository
import com.ninezero.features.commerce.data.CartRow
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.presentation.models.request.CartRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateCartRequest
import com.ninezero.features.commerce.presentation.models.response.CartItemCountResponse
import com.ninezero.features.commerce.presentation.models.response.CartItemResponse
import com.ninezero.features.commerce.presentation.models.response.CartResponse
import com.ninezero.features.commerce.toCartItemResponse
import com.ninezero.features.commerce.toProductResponse
import com.ninezero.features.user.toUserSummaryResponse
import org.jetbrains.exposed.v1.core.eq
import java.math.BigDecimal

class CartService(
    private val cartRepository: CartRepository,
    private val productRepository: ProductRepository,
    private val productAccessService: ProductAccessService
) {

    // 장바구니 조회·담기·수정·삭제
    suspend fun addToCart(userId: Int, request: CartRequest): CartResponse {
        CommerceValidation.validateCartQuantity(request.quantity)

        val cartItemResponses = query {
            // 같은 사용자의 동시 담기 직렬화
            UserDao.find { UserTable.id eq userId }.forUpdate().singleOrNull()
            val existingQuantity = cartRepository.findCartItem(userId, request.productId)?.quantity ?: 0
            CommerceValidation.validateCartQuantity(existingQuantity + request.quantity)  // 담긴 수량과 합산해 상한 검증
            validatePurchasable(userId, request.productId, existingQuantity + request.quantity)  // 기존 수량과 합산해 검증

            cartRepository.createCartItem(userId, request.productId, request.quantity)

            val cartsWithProducts = cartRepository.findUserCartWithProducts(userId)
            mapCartItemResponses(userId, cartsWithProducts)
        }

        return toCartResponse(cartItemResponses)
    }

    suspend fun getUserCart(userId: Int): CartResponse {
        val cartItemResponses = query {
            val cartsWithProducts = cartRepository.findUserCartWithProducts(userId)
            mapCartItemResponses(userId, cartsWithProducts)
        }

        return toCartResponse(cartItemResponses)
    }

    suspend fun updateCartQuantity(cartId: Int, userId: Int, request: UpdateCartRequest): CartResponse {
        CommerceValidation.validateCartQuantity(request.quantity)

        val cartItemResponses = query {
            val cart = cartRepository.findCartById(cartId)
                ?: throw NotFoundException(Errors.Commerce.Cart.CART_ITEM_NOT_FOUND)

            if (cart.userId != userId) {
                throw ForbiddenException(Errors.Commerce.Cart.CART_UPDATE_PERMISSION_DENIED)
            }

            validatePurchasable(userId, cart.productId, request.quantity)  // 변경 수량 기준 재확인

            cartRepository.updateCartItemQuantity(cartId, request.quantity)

            val cartsWithProducts = cartRepository.findUserCartWithProducts(userId)
            mapCartItemResponses(userId, cartsWithProducts)
        }

        return toCartResponse(cartItemResponses)
    }

    suspend fun removeFromCart(cartId: Int, userId: Int) {
        query {
            val cart = cartRepository.findCartById(cartId)
                ?: throw NotFoundException(Errors.Commerce.Cart.CART_ITEM_NOT_FOUND)

            if (cart.userId != userId) {
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

    suspend fun getCartItemCount(userId: Int): CartItemCountResponse {
        return query {
            CartItemCountResponse(count = cartRepository.countCartItems(userId))
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

        val canAccess = productAccessService.canAccess(userId, product)
        if (!canAccess) {
            throw SubscriptionRequiredException(Errors.Commerce.Product.PRODUCT_ACCESS_DENIED)
        }

        CommerceValidation.validateStockAvailability(product.stock, requestedQuantity)
    }

    // =====  공통 보조 =====

    private suspend fun mapCartItemResponses(
        userId: Int,
        cartsWithProducts: List<CartRow>
    ): List<CartItemResponse> {
        if (cartsWithProducts.isEmpty()) return emptyList()

        val accessMap = productAccessService.getAccessMap(userId, cartsWithProducts.map { it.product })

        return cartsWithProducts.map { cartWithProduct ->
            val creatorSummary = cartWithProduct.creator.toUserSummaryResponse()
            val canAccess = accessMap[cartWithProduct.product.id.value] ?: false
            val productResponse = cartWithProduct.product.toProductResponse(creatorSummary, canAccess = canAccess)
            cartWithProduct.cart.toCartItemResponse(productResponse)
        }
    }

    private fun toCartResponse(items: List<CartItemResponse>) = CartResponse(
        items = items,
        totalItems = items.size,
        totalPrice = calcTotalPrice(items).toAmountString()
    )

    private fun calcTotalPrice(items: List<CartItemResponse>): BigDecimal {
        return items.sumOf { BigDecimal(it.subtotal) }
    }
}
