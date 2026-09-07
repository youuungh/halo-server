package com.ninezero.features.social.data

import com.ninezero.core.common.config.CommentSortType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.UserCommentSortType
import com.ninezero.core.database.entities.social.CommentDao

interface CommentRepository {

    // 댓글 생성/수정/삭제
    suspend fun createComment(
        userId: Int,
        postId: Int,
        content: String,
        parentCommentId: Int? = null
    ): CommentDao

    suspend fun updateComment(commentId: Int, content: String): CommentDao?
    suspend fun deleteComment(commentId: Int): Boolean

    // 댓글 조회
    suspend fun findCommentById(commentId: Int): CommentDao?
    suspend fun findCommentsByPostId(
        postId: Int,
        page: Int,
        limit: Int,
        sort: CommentSortType = CommentSortType.POPULAR,
        viewerId: Int? = null
    ): List<CommentDao>

    suspend fun findRepliesByCommentId(
        commentId: Int,
        page: Int,
        limit: Int,
        sort: CommentSortType = CommentSortType.POPULAR,
        viewerId: Int? = null
    ): List<CommentDao>
    suspend fun findUserCommentThreads(
        userId: Int,
        page: Int,
        limit: Int,
        sort: UserCommentSortType = UserCommentSortType.LATEST,
        contextType: PostContextType? = null,
        viewerId: Int? = null
    ): List<CommentDao>
    suspend fun findCommentsByIds(ids: List<Int>): List<CommentDao>
    suspend fun findCommentsByIdsWithDeleted(ids: List<Int>): List<CommentDao>
    suspend fun findRepliesByParentIds(parentIds: List<Int>): List<CommentDao>

    // 카운트
    suspend fun countCommentsByPostId(postId: Int, viewerId: Int? = null): Int
    suspend fun countRepliesByCommentId(commentId: Int, viewerId: Int? = null): Int
    suspend fun countUserComments(
        userId: Int,
        contextType: PostContextType? = null,
        viewerId: Int? = null
    ): Int

    // 좋아요/답글 수 수정
    suspend fun incrementLikeCount(commentId: Int): Boolean
    suspend fun decrementLikeCount(commentId: Int): Boolean
    suspend fun incrementRepliesCount(commentId: Int): Boolean
    suspend fun decrementRepliesCount(commentId: Int): Boolean
}
