package com.ninezero.features.commerce.data

import com.ninezero.core.common.config.ProductSortType
import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.database.entities.commerce.ProductDao
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

/** 탈퇴 삭제 상품의 수거 필드 */
data class SoftDeletedProduct(
    val productId: Int,
    val detailContent: String?
)

interface ProductRepository {

    // 상품 생성
    suspend fun createProduct(
        creatorId: Int,
        name: String,
        description: String,
        price: BigDecimal,
        originalPrice: BigDecimal?,
        stock: Int,
        categoryId: Int?,
        brandName: String?,
        imageUrls: List<String>,
        detailContent: String? = null,
        tags: List<String>,
        requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE
    ): ProductDao

    // 상품 조회
    suspend fun findProductById(productId: Int): ProductDao?
    suspend fun findProductsByIds(productIds: List<Int>): List<ProductDao>

    suspend fun findProductsByIdsWithDeleted(productIds: List<Int>): List<ProductDao>
    suspend fun findProductsByCreator(creatorId: Int, page: Int, limit: Int, sort: ProductSortType = ProductSortType.LATEST): List<ProductDao>
    suspend fun findAllProducts(page: Int, limit: Int): List<ProductDao>
    suspend fun searchProducts(
        query: String?,
        categoryId: Int?,
        brandName: String?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        tags: List<String>?,
        page: Int,
        limit: Int
    ): List<ProductDao>
    suspend fun findPopularProducts(limit: Int): List<ProductDao>
    suspend fun findTrendingProducts(limit: Int): List<ProductDao>

    // 상품 수정
    suspend fun updateProduct(
        productId: Int,
        name: String?,
        description: String?,
        price: BigDecimal?,
        originalPrice: BigDecimal?,
        stock: Int?,
        categoryId: Int?,
        brandName: String?,
        imageUrls: List<String>?,
        detailContent: String?,
        tags: List<String>?,
        status: ProductStatus?,
        requiredTier: SubscriptionPlanTier?
    ): ProductDao?
    suspend fun updateProductStatus(productId: Int, status: ProductStatus): ProductDao?

    // 크리에이터 해제/탈퇴 정리
    suspend fun discontinueProductsByCreator(creatorId: Int): List<Int>

    suspend fun softDeleteProductsByCreator(creatorId: Int): List<SoftDeletedProduct>

    // 타임딜/이미지/상세 수정
    suspend fun setProductDeal(
        productId: Int,
        dealPrice: BigDecimal?,
        startAt: LocalDateTime?,
        endAt: LocalDateTime?
    ): ProductDao?
    suspend fun updateProductImages(productId: Int, imageUrls: List<String>): ProductDao?
    suspend fun updateDetailContent(productId: Int, detailContent: String): ProductDao?

    // 상품 삭제
    suspend fun deleteProduct(productId: Int): Boolean

    // 상품 통계 수정
    suspend fun incrementViewCount(productId: Int)
    suspend fun incrementLikeCount(productId: Int)
    suspend fun decrementLikeCount(productId: Int)
    suspend fun updateReviewStats(productId: Int, reviewCount: Int, rating: BigDecimal)

    // 상품 권한 확인
    suspend fun isProductOwnedBy(productId: Int, userId: Int): Boolean

    // 연관 상품 조회
    suspend fun findRelatedProductsByTags(
        tagIds: List<Int>,
        excludeProductId: Int,
        excludeCreatorId: Int,
        limit: Int
    ): List<ProductDao>

    suspend fun findRelatedProductsByCategory(
        categoryId: Int,
        excludeProductIds: Set<Int>,
        excludeCreatorId: Int,
        limit: Int
    ): List<ProductDao>

    // 카운트
    suspend fun countAllProducts(): Int
    suspend fun countProductsByCreator(creatorId: Int): Int
    suspend fun countProductsByCreatorIds(creatorIds: List<Int>): Map<Int, Int>
    suspend fun countSearchResults(
        query: String?,
        categoryId: Int?,
        brandName: String?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        tags: List<String>?
    ): Int
}
