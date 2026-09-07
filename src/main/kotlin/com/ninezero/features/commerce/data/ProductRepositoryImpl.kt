package com.ninezero.features.commerce.data

import com.ninezero.core.common.config.ProductSortType
import com.ninezero.core.common.config.ProductStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.util.encodeToJson
import com.ninezero.core.common.util.ilike
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.commerce.ProductTable
import com.ninezero.core.database.entities.tag.ProductTagTable
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.math.BigDecimal

class ProductRepositoryImpl : ProductRepository {

    /** 상품 생성 */
    override suspend fun createProduct(
        creatorId: Int,
        name: String,
        description: String,
        price: BigDecimal,
        originalPrice: BigDecimal?,
        stock: Int,
        categoryId: Int?,
        brandName: String?,
        imageUrls: List<String>,
        detailContent: String?,
        tags: List<String>,
        requiredTier: SubscriptionPlanTier
    ): ProductDao {
        val product = ProductDao.new {
            this.creatorId = creatorId
            this.name = name
            this.description = description
            this.price = price
            this.originalPrice = originalPrice
            this.stock = stock
            this.categoryId = categoryId
            this.brandName = brandName
            this.imageUrls = imageUrls.encodeToJson()
            this.detailContent = detailContent
            this.tags = tags.encodeToJson()
            this.status = ProductStatus.ACTIVE
            this.requiredTier = requiredTier
        }

        ProductStatusRules.syncByStock(product)  // 재고 0이면 SOLD_OUT
        return product
    }

    /** 상품 조회 */
    override suspend fun findProductById(productId: Int): ProductDao? {
        // 삭제 상품 제외
        return ProductDao.find {
            (ProductTable.id eq productId) and ProductTable.isActive
        }.firstOrNull()
    }

    /** 삭제 상품 포함 일괄 조회 */
    override suspend fun findProductsByIdsWithDeleted(productIds: List<Int>): List<ProductDao> {
        if (productIds.isEmpty()) return emptyList()

        return ProductDao.find { ProductTable.id inList productIds }.toList()  // 주문내역·상세의 삭제 상품 실명·이미지 유지용
    }

    /** 상품 일괄 조회 */
    override suspend fun findProductsByIds(productIds: List<Int>): List<ProductDao> {
        if (productIds.isEmpty()) return emptyList()

        return ProductDao.find {
            (ProductTable.id inList productIds) and ProductTable.isActive
        }.toList()
    }

    /** 크리에이터 상품 목록 조회 */
    override suspend fun findProductsByCreator(creatorId: Int, page: Int, limit: Int, sort: ProductSortType): List<ProductDao> {
        return ProductDao.find { ProductTable.creatorId eq creatorId }  // isActive 필터 없음
            .orderBy(  // sort별 최신순/가격순
                when (sort) {
                    ProductSortType.LATEST -> ProductTable.createdAt to SortOrder.DESC
                    ProductSortType.PRICE_LOW -> ProductTable.price to SortOrder.ASC
                    ProductSortType.PRICE_HIGH -> ProductTable.price to SortOrder.DESC
                },
                ProductTable.id to SortOrder.DESC
            )
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 상품 전체 목록 조회 */
    override suspend fun findAllProducts(page: Int, limit: Int): List<ProductDao> {
        return ProductDao.find { ProductTable.isActive eq true }
            .orderBy(ProductTable.createdAt to SortOrder.DESC, ProductTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 상품 검색 목록 조회 */
    override suspend fun searchProducts(
        query: String?,
        categoryId: Int?,
        brandName: String?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        tags: List<String>?,
        page: Int,
        limit: Int
    ): List<ProductDao> {
        var conditions = ProductTable.isActive eq true

        query?.let {
            val searchPattern = "%${it}%"
            conditions = conditions and (
                    (ProductTable.name ilike searchPattern) or
                            (ProductTable.description ilike searchPattern)
                    )
        }

        categoryId?.let {
            conditions = conditions and (ProductTable.categoryId eq it)
        }

        brandName?.let {
            conditions = conditions and (ProductTable.brandName eq it)
        }

        minPrice?.let {
            conditions = conditions and (ProductTable.price greaterEq it)
        }

        maxPrice?.let {
            conditions = conditions and (ProductTable.price lessEq it)
        }

        tags?.forEach { tag ->
            val tagPattern = "%$tag%"
            conditions = conditions and (ProductTable.tags like tagPattern)
        }

        return ProductDao.find { conditions }
            .orderBy(ProductTable.createdAt to SortOrder.DESC, ProductTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 상품 부분 수정 */
    override suspend fun updateProduct(
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
    ): ProductDao? {
        val product = ProductDao.findById(productId) ?: return null

        name?.let { product.name = it }  // null은 유지
        description?.let { product.description = it }
        price?.let { product.price = it }
        originalPrice?.let { product.originalPrice = it }
        stock?.let { product.stock = it }
        categoryId?.let { product.categoryId = it }
        brandName?.let { product.brandName = it }
        imageUrls?.let { product.imageUrls = it.encodeToJson() }
        detailContent?.let { product.detailContent = it }
        tags?.let { product.tags = it.encodeToJson() }
        status?.let { product.status = it }
        requiredTier?.let { product.requiredTier = it }

        if (stock != null) {  // stock 넘긴 경우만 status 동기화
            ProductStatusRules.syncByStock(product)
        }

        return product
    }

    /** 상품 status 교체 */
    override suspend fun updateProductStatus(productId: Int, status: ProductStatus): ProductDao? {
        val product = ProductDao.findById(productId) ?: return null
        product.status = status  // 재고 동기화 없음
        return product
    }

    /** 탈퇴 정리용 상품 삭제 */
    override suspend fun softDeleteProductsByCreator(creatorId: Int): List<SoftDeletedProduct> {
        val products = ProductDao.find {
            (ProductTable.creatorId eq creatorId) and (ProductTable.isActive eq true)
        }.map { SoftDeletedProduct(productId = it.id.value, detailContent = it.detailContent) }  // imageUrls는 주문 화면이 사용, 제외

        if (products.isEmpty()) return emptyList()

        ProductTable.update({ ProductTable.id inList products.map { it.productId } }) {
            it[isActive] = false
            it[updatedAt] = nowUtc()
        }

        return products
    }

    /** 크리에이터 해제용 상품 판매종료 */
    override suspend fun discontinueProductsByCreator(creatorId: Int): List<Int> {
        val productIds = ProductTable
            .select(ProductTable.id)
            .where { (ProductTable.creatorId eq creatorId) and (ProductTable.isActive eq true) }
            .map { it[ProductTable.id].value }

        if (productIds.isEmpty()) return emptyList()

        ProductTable.update({ ProductTable.id inList productIds }) {
            it[status] = ProductStatus.DISCONTINUED  // isActive는 그대로
            it[dealPrice] = null
            it[dealStartAt] = null
            it[dealEndAt] = null
            it[updatedAt] = nowUtc()
        }

        return productIds  // 캐시 무효화용
    }

    /** 타임딜 설정/해제 */
    override suspend fun setProductDeal(
        productId: Int,
        dealPrice: BigDecimal?,
        startAt: LocalDateTime?,
        endAt: LocalDateTime?
    ): ProductDao? {
        val product = ProductDao.findById(productId) ?: return null  // null이면 해제, sync 무관
        product.dealPrice = dealPrice
        product.dealStartAt = startAt
        product.dealEndAt = endAt
        return product
    }

    /** 상품 목록 이미지 교체 */
    override suspend fun updateProductImages(productId: Int, imageUrls: List<String>): ProductDao? {
        val product = ProductDao.findById(productId) ?: return null
        product.imageUrls = imageUrls.encodeToJson()
        return product
    }

    /** 상품 상세 콘텐츠 교체 */
    override suspend fun updateDetailContent(productId: Int, detailContent: String): ProductDao? {
        val product = ProductDao.findById(productId) ?: return null
        product.detailContent = detailContent
        return product
    }

    /** 상품 삭제 */
    override suspend fun deleteProduct(productId: Int): Boolean {
        val product = ProductDao.findById(productId) ?: return false
        product.isActive = false
        return true
    }

    /** 상품 조회수 +1 */
    override suspend fun incrementViewCount(productId: Int) {
        ProductTable.update({ ProductTable.id eq productId }) {
            it[viewCount] = viewCount.plus(1)
        }
    }

    /** 상품 likeCount +1 */
    override suspend fun incrementLikeCount(productId: Int) {
        ProductTable.update({ ProductTable.id eq productId }) {
            it[likeCount] = likeCount.plus(1)
        }
    }

    /** 상품 likeCount -1 */
    override suspend fun decrementLikeCount(productId: Int) {
        ProductTable.update({ ProductTable.id eq productId }) {
            it[likeCount] = likeCount.minus(1)  // 0 하한 없음
        }
    }

    /** 리뷰 통계 갱신 */
    override suspend fun updateReviewStats(productId: Int, reviewCount: Int, rating: BigDecimal) {
        ProductTable.update({ ProductTable.id eq productId }) {
            it[ProductTable.reviewCount] = reviewCount  // ReviewService가 재계산한 값
            it[ProductTable.rating] = rating
        }
    }

    /** 태그 기반 연관 상품 조회 */
    override suspend fun findRelatedProductsByTags(
        tagIds: List<Int>,
        excludeProductId: Int,
        excludeCreatorId: Int,
        limit: Int
    ): List<ProductDao> {
        if (tagIds.isEmpty()) return emptyList()

        val tagMatchCount = ProductTagTable.tagId.count().alias("tag_match_count")

        val productIds = (ProductTable innerJoin ProductTagTable)
            .select(ProductTable.id, tagMatchCount)
            .where {
                (ProductTagTable.tagId inList tagIds) and
                (ProductTable.id neq excludeProductId) and
                (ProductTable.creatorId neq excludeCreatorId) and
                (ProductTable.status eq ProductStatus.ACTIVE) and
                (ProductTable.isActive eq true)
            }
            .groupBy(ProductTable.id)
            .orderBy(tagMatchCount to SortOrder.DESC, ProductTable.salesCount to SortOrder.DESC, ProductTable.id to SortOrder.DESC)  // 매칭 수 많은순→판매량순
            .limit(limit)
            .map { it[ProductTable.id].value }

        if (productIds.isEmpty()) return emptyList()

        val products = ProductDao.find { ProductTable.id inList productIds }.toList()
        val orderMap = productIds.withIndex().associate { (index, id) -> id to index }
        return products.sortedBy { orderMap[it.id.value] }
    }

    /** 카테고리 기반 연관 상품 보충 조회 */
    override suspend fun findRelatedProductsByCategory(
        categoryId: Int,
        excludeProductIds: Set<Int>,
        excludeCreatorId: Int,
        limit: Int
    ): List<ProductDao> {
        return ProductDao.find {
            var conditions = (ProductTable.categoryId eq categoryId) and
                (ProductTable.creatorId neq excludeCreatorId) and
                (ProductTable.status eq ProductStatus.ACTIVE) and
                (ProductTable.isActive eq true)

            if (excludeProductIds.isNotEmpty()) {
                conditions = conditions and (ProductTable.id notInList excludeProductIds)
            }

            conditions
        }
            .orderBy(ProductTable.salesCount to SortOrder.DESC, ProductTable.id to SortOrder.DESC)  // 판매량순
            .limit(limit)
            .toList()
    }

    /** 인기 상품 조회 */
    override suspend fun findPopularProducts(limit: Int): List<ProductDao> {
        return ProductDao.find { ProductTable.isActive eq true }
            .orderBy(ProductTable.salesCount to SortOrder.DESC, ProductTable.id to SortOrder.DESC)  // 판매량순
            .limit(limit)
            .toList()
    }

    /** 트렌딩 상품 조회 */
    override suspend fun findTrendingProducts(limit: Int): List<ProductDao> {
        return ProductDao.find { ProductTable.isActive eq true }
            .orderBy(ProductTable.viewCount to SortOrder.DESC, ProductTable.id to SortOrder.DESC)  // 조회수순
            .limit(limit)
            .toList()
    }

    /** 상품 소유 크리에이터 확인 */
    override suspend fun isProductOwnedBy(productId: Int, userId: Int): Boolean {
        val product = ProductDao.findById(productId) ?: return false
        return product.creatorId == userId
    }

    /** 상품 전체 수 */
    override suspend fun countAllProducts(): Int {
        return ProductDao.find { ProductTable.isActive eq true }.count().toInt()
    }

    /** 크리에이터의 상품 수 */
    override suspend fun countProductsByCreator(creatorId: Int): Int {
        return ProductDao.find {
            (ProductTable.creatorId eq creatorId) and (ProductTable.isActive eq true)
        }.count().toInt()
    }

    /** 크리에이터별 상품 수 일괄 조회 */
    override suspend fun countProductsByCreatorIds(creatorIds: List<Int>): Map<Int, Int> {
        if (creatorIds.isEmpty()) return emptyMap()

        return ProductTable.select(ProductTable.creatorId, ProductTable.id.count())
            .where {
                (ProductTable.creatorId inList creatorIds) and
                        (ProductTable.isActive eq true)
            }
            .groupBy(ProductTable.creatorId)
            .associate { it[ProductTable.creatorId] to it[ProductTable.id.count()].toInt() }  // 0건 크리에이터는 키 없음
    }

    /** 검색 상품 수 */
    override suspend fun countSearchResults(
        query: String?,
        categoryId: Int?,
        brandName: String?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        tags: List<String>?
    ): Int {
        var conditions = ProductTable.isActive eq true

        query?.let {
            val searchPattern = "%${it}%"
            conditions = conditions and (
                    (ProductTable.name ilike searchPattern) or
                            (ProductTable.description ilike searchPattern)
                    )
        }

        categoryId?.let {
            conditions = conditions and (ProductTable.categoryId eq it)
        }

        brandName?.let {
            conditions = conditions and (ProductTable.brandName eq it)
        }

        minPrice?.let {
            conditions = conditions and (ProductTable.price greaterEq it)
        }

        maxPrice?.let {
            conditions = conditions and (ProductTable.price lessEq it)
        }

        tags?.forEach { tag ->
            val tagPattern = "%$tag%"
            conditions = conditions and (ProductTable.tags like tagPattern)
        }

        return ProductDao.find { conditions }.count().toInt()
    }
}
