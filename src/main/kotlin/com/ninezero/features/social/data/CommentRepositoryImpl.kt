package com.ninezero.features.social.data

import com.ninezero.core.common.config.CommentSortType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.UserCommentSortType
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.CommentTable
import com.ninezero.core.database.entities.social.PostTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.update

class CommentRepositoryImpl : CommentRepository {

    /** 댓글 생성 */
    override suspend fun createComment(
        userId: Int,
        postId: Int,
        content: String,
        parentCommentId: Int?
    ): CommentDao {
        return CommentDao.new {
            this.userId = userId
            this.postId = postId
            this.content = content
            this.parentCommentId = parentCommentId
        }
    }

    /** 댓글 내용 수정 */
    override suspend fun updateComment(commentId: Int, content: String): CommentDao? {
        val comment = CommentDao.findById(commentId)?.takeIf { it.isActive } ?: return null  // 없거나 비활성이면 null
        comment.content = content
        return comment
    }

    /** 댓글 삭제 */
    override suspend fun deleteComment(commentId: Int): Boolean {
        val comment = CommentDao.findById(commentId) ?: return false
        comment.isActive = false
        return true
    }

    /** 댓글 조회 */
    override suspend fun findCommentById(commentId: Int): CommentDao? {
        return CommentDao.find { (CommentTable.id eq commentId) and (CommentTable.isActive eq true) }.firstOrNull()  // 없거나 비활성이면 null
    }

    /** 포스트의 최상위 댓글 목록 */
    override suspend fun findCommentsByPostId(
        postId: Int,
        page: Int,
        limit: Int,
        sort: CommentSortType,
        viewerId: Int?
    ): List<CommentDao> {
        return CommentDao.find {
            (CommentTable.postId eq postId) and
                    visibleToViewer(viewerId) and  // 활성 + 본인 블라인드 댓글만
                    (CommentTable.parentCommentId.isNull())
        }
            .orderBy(
                when (sort) {
                    CommentSortType.LATEST -> CommentTable.createdAt to SortOrder.DESC
                    CommentSortType.POPULAR -> CommentTable.likeCount to SortOrder.DESC
                },
                CommentTable.id to SortOrder.DESC
            )
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 댓글의 답글 목록 */
    override suspend fun findRepliesByCommentId(
        commentId: Int,
        page: Int,
        limit: Int,
        sort: CommentSortType,
        viewerId: Int?
    ): List<CommentDao> {
        return CommentDao.find {
            (CommentTable.parentCommentId eq commentId) and visibleToViewer(viewerId)
        }
            .orderBy(
                when (sort) {
                    CommentSortType.LATEST -> CommentTable.createdAt to SortOrder.DESC
                    CommentSortType.POPULAR -> CommentTable.likeCount to SortOrder.DESC
                },
                CommentTable.id to SortOrder.DESC
            )
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 유저가 쓴 댓글 목록 */
    override suspend fun findUserCommentThreads(
        userId: Int,
        page: Int,
        limit: Int,
        sort: UserCommentSortType,
        contextType: PostContextType?,
        viewerId: Int?
    ): List<CommentDao> {
        val sortOrder = when (sort) {
            UserCommentSortType.LATEST -> CommentTable.createdAt to SortOrder.DESC
            UserCommentSortType.OLDEST -> CommentTable.createdAt to SortOrder.ASC
        }

        return if (contextType != null) {
            // 비커뮤니티 = GENERAL + CREATOR_FEED 전체
            val query = (CommentTable innerJoin PostTable)
                .select(CommentTable.columns)
                .where {
                    val base = (CommentTable.userId eq userId) and visibleToViewer(viewerId)
                    if (contextType == PostContextType.COMMUNITY) {
                        base and (PostTable.contextType eq PostContextType.COMMUNITY)
                    } else {
                        base and (PostTable.contextType neq PostContextType.COMMUNITY)
                    }
                }
                .orderBy(sortOrder, CommentTable.id to SortOrder.DESC)
                .limit(limit)
                .offset(page.toOffset(limit))

            CommentDao.wrapRows(query).toList()
        } else {
            CommentDao.find {
                (CommentTable.userId eq userId) and visibleToViewer(viewerId)  // 활성 + 본인 블라인드 포함
            }
                .orderBy(sortOrder, CommentTable.id to SortOrder.DESC)
                .limit(limit).offset(page.toOffset(limit))
                .toList()
        }
    }

    /** 댓글 일괄 조회 */
    override suspend fun findCommentsByIds(ids: List<Int>): List<CommentDao> {
        if (ids.isEmpty()) return emptyList()  // 빈 입력은 빈 목록
        return CommentDao.find {
            (CommentTable.id inList ids) and (CommentTable.isActive eq true)
        }.toList()
    }

    /** 댓글 일괄 조회 */
    override suspend fun findCommentsByIdsWithDeleted(ids: List<Int>): List<CommentDao> {
        if (ids.isEmpty()) return emptyList()
        return CommentDao.find {
            CommentTable.id inList ids  // 삭제·블라인드 포함
        }.toList()
    }

    /** 부모별 답글 일괄 조회 */
    override suspend fun findRepliesByParentIds(parentIds: List<Int>): List<CommentDao> {
        if (parentIds.isEmpty()) return emptyList()
        return CommentDao.find {
            (CommentTable.parentCommentId inList parentIds) and (CommentTable.isActive eq true)
        }
            .orderBy(CommentTable.createdAt to SortOrder.ASC, CommentTable.id to SortOrder.DESC)  // 오름차순
            .toList()
    }

    /** 포스트의 댓글 수 */
    override suspend fun countCommentsByPostId(postId: Int, viewerId: Int?): Int {
        return CommentDao.find {
            (CommentTable.postId eq postId) and visibleToViewer(viewerId)  // 답글 포함
        }.count().toInt()
    }

    /** 댓글의 답글 수 */
    override suspend fun countRepliesByCommentId(commentId: Int, viewerId: Int?): Int {
        return CommentDao.find {
            (CommentTable.parentCommentId eq commentId) and visibleToViewer(viewerId)
        }.count().toInt()
    }

    /** 유저가 쓴 댓글 수 */
    override suspend fun countUserComments(
        userId: Int,
        contextType: PostContextType?,
        viewerId: Int?
    ): Int {
        return if (contextType != null) {
            (CommentTable innerJoin PostTable)
                .select(CommentTable.id)
                .where {
                    val base = (CommentTable.userId eq userId) and visibleToViewer(viewerId)
                    if (contextType == PostContextType.COMMUNITY) {
                        base and (PostTable.contextType eq PostContextType.COMMUNITY)
                    } else {
                        base and (PostTable.contextType neq PostContextType.COMMUNITY)
                    }
                }
                .count().toInt()
        } else {
            CommentDao.find {
                (CommentTable.userId eq userId) and visibleToViewer(viewerId)
            }.count().toInt()
        }
    }

    /** 댓글 likeCount +1 */
    override suspend fun incrementLikeCount(commentId: Int): Boolean {
        val updated = CommentTable.update({ CommentTable.id eq commentId }) {
            it[likeCount] = likeCount.plus(1)
        }
        return updated > 0
    }

    /** 댓글 likeCount -1 */
    override suspend fun decrementLikeCount(commentId: Int): Boolean {
        val updated = CommentTable.update({ CommentTable.id eq commentId }) {
            it[likeCount] = likeCount.minus(1)
        }
        return updated > 0
    }

    /** 댓글 replyCount +1 */
    override suspend fun incrementRepliesCount(commentId: Int): Boolean {
        val updated = CommentTable.update({ CommentTable.id eq commentId }) {
            it[replyCount] = replyCount.plus(1)
        }
        return updated > 0
    }

    /** 댓글 replyCount -1 */
    override suspend fun decrementRepliesCount(commentId: Int): Boolean {
        val updated = CommentTable.update({ CommentTable.id eq commentId }) {
            it[replyCount] = replyCount.minus(1)
        }
        return updated > 0
    }
}

private fun SqlExpressionBuilder.visibleToViewer(viewerId: Int?): Op<Boolean> =
    if (viewerId == null) {
        CommentTable.isActive eq true
    } else {
        (CommentTable.isActive eq true) or  // viewer 있으면 본인 블라인드도 포함
            ((CommentTable.isBlinded eq true) and (CommentTable.userId eq viewerId))
    }
