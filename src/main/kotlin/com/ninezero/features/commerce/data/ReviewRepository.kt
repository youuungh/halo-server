package com.ninezero.features.commerce.data

import com.ninezero.core.common.config.ReviewSortType
import com.ninezero.core.database.entities.commerce.ReviewDao
import com.ninezero.core.database.entities.user.UserDao

data class ReviewWithAuthor(
    val review: ReviewDao,
    val author: UserDao
)

interface ReviewRepository {

    // 리뷰 생성/수정/삭제
    suspend fun createReview(
        userId: Int,
        productId: Int,
        orderId: Int,
        rating: Int,
        content: String,
        images: List<String>
    ): ReviewDao

    suspend fun updateReview(
        reviewId: Int,
        rating: Int?,
        content: String?,
        images: List<String>?
    ): ReviewDao?

    suspend fun deleteReview(reviewId: Int): Boolean
    suspend fun hardDeleteReview(reviewId: Int): Boolean

    // 리뷰 조회
    suspend fun findReviewById(reviewId: Int): ReviewDao?
    suspend fun findReviewByOrderProduct(orderId: Int, productId: Int): ReviewDao?
    suspend fun findReviewIdsByOrder(orderId: Int): Map<Int, Int>
    suspend fun findReviewsWithAuthors(
        productId: Int,
        page: Int,
        limit: Int,
        sortBy: ReviewSortType = ReviewSortType.RECENT
    ): List<ReviewWithAuthor>

    suspend fun findReviewsByUserId(userId: Int, page: Int, limit: Int): List<ReviewDao>

    // 리뷰 통계
    suspend fun countReviewsByUserId(userId: Int): Long
    suspend fun countReviewsByProductId(productId: Int): Long
    suspend fun getAvgRating(productId: Int): Double
    suspend fun getRatingDistribution(productId: Int): Map<Int, Int>
}
