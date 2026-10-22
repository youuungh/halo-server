package com.ninezero.features.social.data

import com.ninezero.core.common.config.CommentSortType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.UserCommentSortType
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.database.entities.social.CommentDao

interface CommentRepository {

    // 댓글 생성/수정/삭제
    suspend fun createComment(
        userId: Int,
        postId: Int,
        content: String,
        parentCommentId: Int? = null,
        clientRequestId: String? = null
    ): CommentDao

    suspend fun updateComment(commentId: Int, content: String): CommentDao?

    // 댓글 조회
    suspend fun findCommentById(commentId: Int): CommentDao?
    suspend fun findCommentByClientRequestId(userId: Int, clientRequestId: String): CommentDao?
    suspend fun findThreadCommentById(commentId: Int, viewerId: Int?): CommentDao?
    suspend fun hasThreadReplies(commentId: Int): Boolean
    suspend fun findCommentsByPostId(
        postId: Int,
        page: Int,
        limit: Int,
        sort: CommentSortType,
        viewerId: Int?
    ): List<CommentDao>

    suspend fun findRepliesByCommentId(
        commentId: Int,
        page: Int,
        limit: Int,
        sort: CommentSortType,
        viewerId: Int?
    ): List<CommentDao>
    suspend fun findUserComments(
        userId: Int,
        cursor: PageCursor?,
        limit: Int,
        sort: UserCommentSortType,
        contextType: PostContextType?,
        viewerId: Int?
    ): List<CommentDao>
    suspend fun findCommentsByIds(ids: Collection<Int>): List<CommentDao>
    suspend fun findCommentsByIdsWithDeleted(ids: Collection<Int>): List<CommentDao>
    suspend fun findRepliesByParentIds(parentIds: Collection<Int>): List<CommentDao>

    // 카운트
    suspend fun countCommentsByPostId(postId: Int, viewerId: Int?): Int
    suspend fun countRepliesByCommentId(commentId: Int, viewerId: Int?): Int

    // 좋아요/답글 수 수정
    suspend fun incrementLikeCount(commentId: Int): Boolean
    suspend fun decrementLikeCount(commentId: Int): Boolean
    suspend fun incrementRepliesCount(commentId: Int): Boolean
    suspend fun decrementRepliesCount(commentId: Int): Boolean
    suspend fun removeEmptyPlaceholders(parentId: Int?)
}
