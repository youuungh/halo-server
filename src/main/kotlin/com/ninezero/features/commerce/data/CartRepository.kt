package com.ninezero.features.commerce.data

import com.ninezero.core.database.entities.commerce.CartDao
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.user.UserDao

data class CartRow(
    val cart: CartDao,
    val product: ProductDao,
    val creator: UserDao
)

interface CartRepository {

    // 장바구니 생성/수정/삭제
    suspend fun createCartItem(userId: Int, productId: Int, quantity: Int): CartDao
    suspend fun updateCartItemQuantity(cartId: Int, quantity: Int): CartDao?
    suspend fun deleteCartItem(cartId: Int): Boolean
    suspend fun deleteCartItems(cartIds: List<Int>): Boolean
    suspend fun clearUserCart(userId: Int): Boolean

    // 장바구니 조회
    suspend fun findCartById(cartId: Int): CartDao?
    suspend fun findUserCart(userId: Int): List<CartDao>
    suspend fun findUserCartWithProducts(userId: Int): List<CartRow>
    suspend fun findCartItem(userId: Int, productId: Int): CartDao?

    // 권한 확인
    suspend fun isCartOwnedBy(cartId: Int, userId: Int): Boolean

    // 카운트
    suspend fun countCartItems(userId: Int): Int
}
