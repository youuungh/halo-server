package com.ninezero.features.commerce.data

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ProductNotFoundException
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.afterNewest
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.commerce.ProductTable
import com.ninezero.core.database.entities.commerce.WishlistDao
import com.ninezero.core.database.entities.commerce.WishlistTable
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.update

class WishlistRepositoryImpl : WishlistRepository {

    /** 위시 삭제 */
    override suspend fun deleteWishlist(userId: Int, productId: Int): Boolean {
        val wishlist = WishlistDao.find {
            (WishlistTable.userId eq userId) and
                    (WishlistTable.productId eq productId) and
                    (WishlistTable.isActive eq true)
        }.firstOrNull() ?: return false

        wishlist.isActive = false
        decrementLikeCount(productId)
        return true
    }

    /** 위시 토글 */
    override suspend fun toggleWishlist(
        userId: Int,
        productId: Int
    ): WishlistToggleResult {
        val product = ProductDao.findById(productId)
            ?: throw ProductNotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

        val existingWishlist = WishlistDao.find {
            (WishlistTable.userId eq userId) and (WishlistTable.productId eq productId)
        }.firstOrNull()

        val isCurrentlyWishlisted = existingWishlist?.isActive == true

        // 삭제된 상품은 새로 담지 못하고 빼기만 가능
        if (!product.isActive && !isCurrentlyWishlisted) {
            throw ProductNotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)
        }

        val isWishlisted = if (isCurrentlyWishlisted) {
            existingWishlist.isActive = false
            decrementLikeCount(productId)
            false
        } else {
            if (existingWishlist == null) {
                WishlistDao.new {
                    this.userId = userId
                    this.productId = productId
                    this.isActive = true
                }
            } else {
                existingWishlist.isActive = true
            }
            incrementLikeCount(productId)
            true
        }

        val wishlistCount = WishlistDao.find {
            (WishlistTable.userId eq userId) and (WishlistTable.isActive eq true)
        }.count().toInt()

        return WishlistToggleResult(
            isWishlisted = isWishlisted,
            wishlistCount = wishlistCount
        )
    }

    /** 탈퇴 정리용 위시 비활성화 */
    override suspend fun deactivateAllByUser(userId: Int): Int {
        val rows = WishlistDao.find {
            (WishlistTable.userId eq userId) and (WishlistTable.isActive eq true)
        }.toList()

        rows.forEach { row ->
            decrementLikeCount(row.productId)
            row.isActive = false
        }

        return rows.size
    }

    /** 유저·상품의 위시 존재 여부 */
    override suspend fun isInWishlist(userId: Int, productId: Int): Boolean {
        return !WishlistDao.find {
            (WishlistTable.userId eq userId) and
                    (WishlistTable.productId eq productId) and
                    (WishlistTable.isActive eq true)
        }.empty()
    }

    /** 유저 위시 목록 조회 */
    override suspend fun findWishlistWithProducts(
        userId: Int,
        cursor: PageCursor?,
        limit: Int
    ): List<WishlistRow> {
        val wishlists = WishlistDao.find {
            val afterOp = cursor?.let { afterNewest(WishlistTable.createdAt, WishlistTable.id, it) } ?: Op.TRUE
            (WishlistTable.userId eq userId) and (WishlistTable.isActive eq true) and afterOp
        }
            .orderBy(WishlistTable.createdAt to SortOrder.DESC, WishlistTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .toList()

        if (wishlists.isEmpty()) return emptyList()

        val productIds = wishlists.map { it.productId }.distinct()
        val products = ProductDao.find { ProductTable.id inList productIds }.associateBy { it.id.value }
        val creatorIds = products.values.map { it.creatorId }.distinct()
        val creators = UserDao.find { UserTable.id inList creatorIds }.associateBy { it.id.value }

        return wishlists.mapNotNull { wishlist ->  // 상품·크리에이터 없으면 제외
            val product = products[wishlist.productId] ?: return@mapNotNull null
            val creator = creators[product.creatorId] ?: return@mapNotNull null
            WishlistRow(wishlist, product, creator)
        }
    }

    /** 찜 수 +1 */
    private fun incrementLikeCount(productId: Int) {
        ProductTable.update({ ProductTable.id eq productId }) {
            it[likeCount] = likeCount.plus(1)
        }
    }

    /** 찜 수 -1, 0 하한 */
    private fun decrementLikeCount(productId: Int) {
        ProductTable.update({ (ProductTable.id eq productId) and (ProductTable.likeCount greater 0) }) {
            it[likeCount] = likeCount.minus(1)
        }
    }
}
