package com.ninezero.features.commerce.data

import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.commerce.ProductTable
import com.ninezero.core.database.entities.commerce.WishlistDao
import com.ninezero.core.database.entities.commerce.WishlistTable
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and

class WishlistRepositoryImpl : WishlistRepository {

    /** 위시 추가 */
    override suspend fun createWishlist(userId: Int, productId: Int): WishlistDao? {
        val existing = WishlistDao.find {  // 상품 likeCount는 건드리지 않음
            (WishlistTable.userId eq userId) and (WishlistTable.productId eq productId)
        }.firstOrNull()

        return when {  // 없으면 생성/비활성이면 재활성화/활성이면 null
            existing == null -> {
                WishlistDao.new {
                    this.userId = userId
                    this.productId = productId
                    this.isActive = true
                }
            }
            !existing.isActive -> {
                existing.isActive = true
                existing
            }
            else -> null
        }
    }

    /** 위시 삭제 */
    override suspend fun deleteWishlist(userId: Int, productId: Int): Boolean {
        val wishlist = WishlistDao.find {
            (WishlistTable.userId eq userId) and
                    (WishlistTable.productId eq productId) and
                    (WishlistTable.isActive eq true)
        }.firstOrNull() ?: return false

        wishlist.isActive = false  // likeCount는 건드리지 않음
        return true
    }

    /** 탈퇴 정리용 위시 비활성화 */
    override suspend fun deactivateAllByUser(userId: Int): Int {
        val rows = WishlistDao.find {
            (WishlistTable.userId eq userId) and (WishlistTable.isActive eq true)
        }.toList()

        rows.forEach { row ->
            ProductDao.findById(row.productId)?.let { product ->
                product.likeCount = maxOf(0, product.likeCount - 1)  // 0 하한
            }
            row.isActive = false
        }

        return rows.size
    }

    /** 위시 토글 */
    override suspend fun toggleWishlist(
        userId: Int,
        productId: Int
    ): WishlistToggleResult {
        val product = ProductDao.findById(productId)
            ?: throw IllegalStateException("상품을 찾을 수 없습니다. (ID: $productId)")

        if (!product.isActive) {
            throw IllegalStateException("삭제된 상품입니다.")  // 삭제 상품은 예외
        }

        val existingWishlist = WishlistDao.find {
            (WishlistTable.userId eq userId) and (WishlistTable.productId eq productId)
        }.firstOrNull()

        val isCurrentlyWishlisted = existingWishlist?.isActive == true

        val isWishlisted = if (isCurrentlyWishlisted) {
            existingWishlist.isActive = false
            product.likeCount = maxOf(0, product.likeCount - 1)  // 해제시 likeCount -1
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
            product.likeCount += 1  // 생성/재활성화시 +1
            true
        }

        val wishlistCount = WishlistDao.find {
            (WishlistTable.userId eq userId) and (WishlistTable.isActive eq true)
        }.count().toInt()

        return WishlistToggleResult(
            isWishlisted = isWishlisted,
            wishlistCount = wishlistCount,
            productLikeCount = product.likeCount
        )
    }

    /** 유저·상품의 위시 존재 여부 */
    override suspend fun isInWishlist(userId: Int, productId: Int): Boolean {
        return WishlistDao.find {
            (WishlistTable.userId eq userId) and
                    (WishlistTable.productId eq productId) and
                    (WishlistTable.isActive eq true)
        }.count() > 0
    }

    /** 유저 위시 목록 조회 */
    override suspend fun findWishlistWithProducts(
        userId: Int,
        page: Int,
        limit: Int
    ): List<WishlistRow> {
        val wishlists = WishlistDao.find {
            (WishlistTable.userId eq userId) and (WishlistTable.isActive eq true)
        }
            .orderBy(WishlistTable.createdAt to SortOrder.DESC, WishlistTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
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

    /** 유저 위시의 productId 목록 조회 */
    override suspend fun findWishlistProductIds(userId: Int, page: Int, limit: Int): List<Int> {
        return WishlistDao.find {
            (WishlistTable.userId eq userId) and (WishlistTable.isActive eq true)
        }
            .orderBy(WishlistTable.createdAt to SortOrder.DESC, WishlistTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .map { it.productId }
    }

    /** 유저 위시 수 */
    override suspend fun countWishlist(userId: Int): Int {
        return WishlistDao.find {
            (WishlistTable.userId eq userId) and (WishlistTable.isActive eq true)
        }.count().toInt()
    }
}
