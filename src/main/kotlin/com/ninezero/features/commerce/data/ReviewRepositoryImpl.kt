package com.ninezero.features.commerce.data

import com.ninezero.core.common.config.ReviewSortType
import com.ninezero.core.common.util.encodeToJson
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.afterCursor
import com.ninezero.core.common.util.afterNewest
import com.ninezero.core.database.entities.commerce.ReviewDao
import com.ninezero.core.database.entities.commerce.ReviewTable
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.avg
import org.jetbrains.exposed.sql.count

class ReviewRepositoryImpl : ReviewRepository {

    /** 리뷰 생성 */
    override suspend fun createReview(
        userId: Int,
        productId: Int,
        orderId: Int,
        rating: Int,
        content: String,
        images: List<String>
    ): ReviewDao {
        return ReviewDao.new {
            this.userId = userId
            this.productId = productId
            this.orderId = orderId
            this.rating = rating
            this.content = content
            this.images = images.encodeToJson()
            this.isActive = true
        }
    }

    /** 리뷰 수정 */
    override suspend fun updateReview(
        reviewId: Int,
        rating: Int?,
        content: String?,
        images: List<String>?
    ): ReviewDao? {
        val review = ReviewDao.findById(reviewId) ?: return null

        rating?.let { review.rating = it }  // null은 유지
        content?.let { review.content = it }
        images?.let { review.images = it.encodeToJson() }

        return review
    }

    /** 리뷰 삭제 */
    override suspend fun deleteReview(reviewId: Int): Boolean {
        val review = ReviewDao.findById(reviewId) ?: return false
        review.isActive = false
        return true
    }

    /** 리뷰 영구 삭제 */
    override suspend fun hardDeleteReview(reviewId: Int): Boolean {
        val review = ReviewDao.findById(reviewId) ?: return false
        review.delete()
        return true
    }

    /** 리뷰 조회 */
    override suspend fun findReviewById(reviewId: Int): ReviewDao? {
        return ReviewDao.find {
            (ReviewTable.id eq reviewId) and (ReviewTable.isActive eq true)
        }.firstOrNull()
    }

    /** 주문·상품 조합의 리뷰 조회 */
    override suspend fun findReviewByOrderProduct(orderId: Int, productId: Int): ReviewDao? {
        return ReviewDao.find {
            (ReviewTable.orderId eq orderId) and
                    (ReviewTable.productId eq productId)
        }.firstOrNull()  // 삭제 리뷰 포함
    }

    /** 주문별 리뷰 ID 조회 */
    override suspend fun findReviewIdsByOrder(orderId: Int): Map<Int, Int> {
        return ReviewDao.find { ReviewTable.orderId eq orderId }
            .associate { it.productId to it.id.value }  // 삭제 리뷰 포함
    }

    /** 여러 주문의 리뷰 ID 일괄 조회, 주문별 상품→리뷰 */
    override suspend fun findReviewIdsByOrders(orderIds: Collection<Int>): Map<Int, Map<Int, Int>> {
        if (orderIds.isEmpty()) return emptyMap()
        return ReviewDao.find { ReviewTable.orderId inList orderIds }
            .groupBy { it.orderId }
            .mapValues { (_, reviews) -> reviews.associate { it.productId to it.id.value } }  // 삭제 리뷰 포함
    }

    /** 상품 리뷰 목록 조회 */
    override suspend fun findReviewsWithAuthors(
        productId: Int,
        cursor: PageCursor?,
        limit: Int,
        sortBy: ReviewSortType
    ): List<ReviewWithAuthor> {
        val baseQuery = ReviewDao.find {
            val afterOp = cursor?.let { afterReview(sortBy, it) } ?: Op.TRUE
            (ReviewTable.productId eq productId) and (ReviewTable.isActive eq true) and afterOp
        }

        val reviews = when (sortBy) {
            ReviewSortType.RECENT -> {
                baseQuery.orderBy(ReviewTable.createdAt to SortOrder.DESC, ReviewTable.id to SortOrder.DESC)
            }
            ReviewSortType.RATING_HIGH -> {
                baseQuery.orderBy(ReviewTable.rating to SortOrder.DESC, ReviewTable.createdAt to SortOrder.DESC, ReviewTable.id to SortOrder.DESC)
            }
            ReviewSortType.RATING_LOW -> {
                baseQuery.orderBy(ReviewTable.rating to SortOrder.ASC, ReviewTable.createdAt to SortOrder.DESC, ReviewTable.id to SortOrder.DESC)
            }
        }
            .limit(limit)
            .toList()

        if (reviews.isEmpty()) return emptyList()

        val userIds = reviews.map { it.userId }.distinct()
        val users = UserDao.find { UserTable.id inList userIds }.associateBy { it.id.value }

        return reviews.mapNotNull { review ->
            val author = users[review.userId] ?: return@mapNotNull null  // 작성자 없으면 제외
            ReviewWithAuthor(review, author)
        }
    }

    // 별점순 커서는 별점 뒤에 시각·id
    private fun afterReview(sortBy: ReviewSortType, cursor: PageCursor): Op<Boolean> {
        if (sortBy == ReviewSortType.RECENT) return afterNewest(ReviewTable.createdAt, ReviewTable.id, cursor)
        val rating = cursor.int(0)
        val at = cursor.dateTime(1)
        val lastId = cursor.int(2)
        val ratingPassed = if (sortBy == ReviewSortType.RATING_HIGH) ReviewTable.rating less rating else ReviewTable.rating greater rating
        return afterCursor(
            ratingPassed to (ReviewTable.rating eq rating),
            (ReviewTable.createdAt less at) to (ReviewTable.createdAt eq at),
            (ReviewTable.id less lastId) to (ReviewTable.id eq lastId)
        )
    }

    /** 유저의 리뷰 목록 조회 */
    override suspend fun findReviewsByUserId(
        userId: Int,
        cursor: PageCursor?,
        limit: Int
    ): List<ReviewDao> {
        return ReviewDao.find {
            val afterOp = cursor?.let { afterNewest(ReviewTable.createdAt, ReviewTable.id, it) } ?: Op.TRUE
            (ReviewTable.userId eq userId) and (ReviewTable.isActive eq true) and afterOp
        }
            .orderBy(ReviewTable.createdAt to SortOrder.DESC, ReviewTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .toList()
    }

    /** 상품의 리뷰 수 */
    override suspend fun countReviewsByProductId(productId: Int): Long {
        return ReviewDao.find {
            (ReviewTable.productId eq productId) and (ReviewTable.isActive eq true)
        }.count()
    }

    /** 상품 리뷰 평점 평균 */
    override suspend fun getAvgRating(productId: Int): Double {
        return ReviewTable
            .select(ReviewTable.rating.avg())
            .where { (ReviewTable.productId eq productId) and (ReviewTable.isActive eq true) }
            .firstOrNull()
            ?.get(ReviewTable.rating.avg())
            ?.toDouble() ?: 0.0
    }

    /** 상품 리뷰의 평점 1~5별 건수 */
    override suspend fun getRatingDistribution(productId: Int): Map<Int, Int> {
        val distribution = (1..5).associateWith { 0 }.toMutableMap()  // 없는 평점은 0으로 채움

        val ratingCount = ReviewTable.rating.count()
        ReviewTable
            .select(ReviewTable.rating, ratingCount)
            .where { (ReviewTable.productId eq productId) and (ReviewTable.isActive eq true) }
            .groupBy(ReviewTable.rating)
            .forEach { row ->
                distribution[row[ReviewTable.rating]] = row[ratingCount].toInt()
            }

        return distribution
    }
}
