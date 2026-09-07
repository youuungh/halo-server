package com.ninezero.features.commerce.data

import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.commerce.WishlistDao
import com.ninezero.core.database.entities.user.UserDao

data class WishlistRow(
    val wishlist: WishlistDao,
    val product: ProductDao,
    val creator: UserDao
)

data class WishlistToggleResult(
    val isWishlisted: Boolean,
    val wishlistCount: Int,
    val productLikeCount: Int
)

interface WishlistRepository {

    // 위시리스트 생성/삭제
    suspend fun createWishlist(userId: Int, productId: Int): WishlistDao?
    suspend fun deleteWishlist(userId: Int, productId: Int): Boolean
    suspend fun toggleWishlist(
        userId: Int,
        productId: Int
    ): WishlistToggleResult

    // 탈퇴 정리
    suspend fun deactivateAllByUser(userId: Int): Int

    // 위시리스트 조회
    suspend fun isInWishlist(userId: Int, productId: Int): Boolean
    suspend fun findWishlistWithProducts(
        userId: Int,
        page: Int,
        limit: Int
    ): List<WishlistRow>
    suspend fun findWishlistProductIds(userId: Int, page: Int, limit: Int): List<Int>

    // 카운트
    suspend fun countWishlist(userId: Int): Int
}
