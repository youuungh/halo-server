package com.ninezero.features.social.data

import com.ninezero.core.common.config.LikeType
import com.ninezero.core.database.entities.social.LikeDao

interface LikeRepository {

    // 좋아요 생성/삭제
    suspend fun createPostLike(userId: Int, postId: Int): LikeDao?
    suspend fun createCommentLike(userId: Int, commentId: Int): LikeDao?
    suspend fun deleteLike(userId: Int, targetId: Int, targetType: LikeType): Boolean

    // 좋아요 조회
    suspend fun isPostLiked(userId: Int, postId: Int): Boolean
    suspend fun isCommentLiked(userId: Int, commentId: Int): Boolean
    suspend fun checkMultiplePostLikes(userId: Int, postIds: List<Int>): Map<Int, Boolean>
    suspend fun checkMultipleCommentLikes(userId: Int, commentIds: List<Int>): Map<Int, Boolean>
    suspend fun findPostLikes(postId: Int, page: Int, limit: Int): List<LikeDao>
    suspend fun findCommentLikes(commentId: Int, page: Int, limit: Int): List<LikeDao>
    suspend fun findUserLikedPosts(userId: Int, page: Int, limit: Int): List<Int>
    suspend fun findUserLikes(userId: Int, page: Int, limit: Int, targetType: LikeType? = null): List<LikeDao>

    // 카운트
    suspend fun countUserLikes(userId: Int, targetType: LikeType? = null): Int
    suspend fun countPostLikes(postId: Int): Int
    suspend fun countCommentLikes(commentId: Int): Int
}
