package com.ninezero.features.commerce.data

import com.ninezero.core.database.entities.commerce.CartDao
import com.ninezero.core.database.entities.commerce.CartTable
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.commerce.ProductTable
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere

class CartRepositoryImpl : CartRepository {

    /** 장바구니 담기 */
    override suspend fun createCartItem(userId: Int, productId: Int, quantity: Int): CartDao {
        val existingCart = CartDao.find {
            (CartTable.userId eq userId) and (CartTable.productId eq productId)
        }.singleOrNull()

        return if (existingCart != null) {
            existingCart.quantity += quantity  // 이미 있으면 수량 누적
            existingCart
        } else {
            CartDao.new {
                this.userId = userId
                this.productId = productId
                this.quantity = quantity
            }
        }
    }

    /** 장바구니 항목 수량 교체 */
    override suspend fun updateCartItemQuantity(cartId: Int, quantity: Int): CartDao? {
        val cart = CartDao.findById(cartId) ?: return null
        cart.quantity = quantity
        return cart
    }

    /** 장바구니 항목 삭제 */
    override suspend fun deleteCartItem(cartId: Int): Boolean {
        val cart = CartDao.findById(cartId) ?: return false
        cart.delete()
        return true
    }

    /** 장바구니 항목 일괄 삭제 */
    override suspend fun deleteCartItems(cartIds: List<Int>): Boolean {
        if (cartIds.isEmpty()) return false
        val items = CartDao.forIds(cartIds).toList()
        items.forEach { it.delete() }
        return items.isNotEmpty()
    }

    /** 유저 장바구니 전체 삭제 */
    override suspend fun clearUserCart(userId: Int): Boolean {
        return CartTable.deleteWhere { CartTable.userId eq userId } > 0
    }

    /** 장바구니 항목 조회 */
    override suspend fun findCartById(cartId: Int): CartDao? {
        return CartDao.findById(cartId)
    }

    /** 유저 장바구니 항목 목록 조회 */
    override suspend fun findUserCart(userId: Int): List<CartDao> {
        return CartDao.find { CartTable.userId eq userId }
            .orderBy(CartTable.createdAt to SortOrder.DESC, CartTable.id to SortOrder.DESC)  // 최신순
            .toList()
    }

    /** 유저 장바구니 상품 조회 */
    override suspend fun findUserCartWithProducts(userId: Int): List<CartRow> {
        val carts = CartDao.find { CartTable.userId eq userId }
            .orderBy(CartTable.createdAt to SortOrder.DESC, CartTable.id to SortOrder.DESC)  // 최신순
            .toList()

        if (carts.isEmpty()) return emptyList()

        val productIds = carts.map { it.productId }.distinct()
        val products = ProductDao.find { ProductTable.id inList productIds }.associateBy { it.id.value }
        val creatorIds = products.values.map { it.creatorId }.distinct()
        val creators = UserDao.find { UserTable.id inList creatorIds }.associateBy { it.id.value }

        return carts.mapNotNull { cart ->  // 상품·크리에이터 없으면 제외
            val product = products[cart.productId] ?: return@mapNotNull null
            val creator = creators[product.creatorId] ?: return@mapNotNull null
            CartRow(cart, product, creator)
        }
    }

    /** 유저·상품 조합의 장바구니 항목 조회 */
    override suspend fun findCartItem(userId: Int, productId: Int): CartDao? {
        return CartDao.find {
            (CartTable.userId eq userId) and (CartTable.productId eq productId)
        }.singleOrNull()
    }

    /** 장바구니 항목 소유자 확인 */
    override suspend fun isCartOwnedBy(cartId: Int, userId: Int): Boolean {
        val cart = CartDao.findById(cartId) ?: return false
        return cart.userId == userId
    }

    /** 유저 장바구니 항목 수 */
    override suspend fun countCartItems(userId: Int): Int {
        return CartDao.find { CartTable.userId eq userId }.count().toInt()
    }
}
