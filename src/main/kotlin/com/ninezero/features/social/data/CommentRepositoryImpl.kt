package com.ninezero.features.social.data

import com.ninezero.core.common.config.CommentSortType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.UserCommentSortType
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.afterNewest
import com.ninezero.core.common.util.afterOldest
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
        parentCommentId: Int?,
        clientRequestId: String?
    ): CommentDao {
        return CommentDao.new {
            this.userId = userId
            this.postId = postId
            this.content = content
            this.parentCommentId = parentCommentId
            this.clientRequestId = clientRequestId
        }
    }

    /** 댓글 내용 수정 */
    override suspend fun updateComment(commentId: Int, content: String): CommentDao? {
        val comment = CommentDao.findById(commentId)?.takeIf { it.isActive } ?: return null  // 없거나 비활성이면 null
        comment.content = content
        return comment
    }

    /** 댓글 조회 */
    override suspend fun findCommentById(commentId: Int): CommentDao? {
        return CommentDao.find { (CommentTable.id eq commentId) and (CommentTable.isActive eq true) }.firstOrNull()  // 없거나 비활성이면 null
    }

    /** 같은 요청 식별자로 이미 만든 댓글 */
    override suspend fun findCommentByClientRequestId(userId: Int, clientRequestId: String): CommentDao? {
        return CommentDao.find {
            (CommentTable.userId eq userId) and (CommentTable.clientRequestId eq clientRequestId) and (CommentTable.isActive eq true)
        }.firstOrNull()
    }

    /** 삭제 자리까지 포함한 댓글 조회 */
    override suspend fun findThreadCommentById(commentId: Int): CommentDao? {
        return CommentDao.find { (CommentTable.id eq commentId) and shownInThread() }.firstOrNull()
    }

    /** 스레드에 남은 답글(삭제 자리 포함) 여부 */
    override suspend fun hasThreadReplies(commentId: Int): Boolean {
        return !CommentDao.find { (CommentTable.parentCommentId eq commentId) and shownInThread() }.empty()
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
                    visibleInThread(viewerId) and  // 활성 + 본인 블라인드 + 삭제 자리
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
            (CommentTable.parentCommentId eq commentId) and visibleInThread(viewerId)
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
    override suspend fun findUserComments(
        userId: Int,
        cursor: PageCursor?,
        limit: Int,
        sort: UserCommentSortType,
        contextType: PostContextType?,
        viewerId: Int?
    ): List<CommentDao> {
        val sortOrder = when (sort) {
            UserCommentSortType.LATEST -> CommentTable.createdAt to SortOrder.DESC
            UserCommentSortType.OLDEST -> CommentTable.createdAt to SortOrder.ASC
        }
        val afterOp = cursor?.let {
            when (sort) {
                UserCommentSortType.LATEST -> afterNewest(CommentTable.createdAt, CommentTable.id, it)
                UserCommentSortType.OLDEST -> afterOldest(CommentTable.createdAt, CommentTable.id, it)
            }
        } ?: Op.TRUE

        return if (contextType != null) {
            // 비커뮤니티 = GENERAL + CREATOR_FEED 전체
            val query = (CommentTable innerJoin PostTable)
                .select(CommentTable.columns)
                .where {
                    val base = (CommentTable.userId eq userId) and visibleToViewer(viewerId) and afterOp
                    if (contextType == PostContextType.COMMUNITY) {
                        base and (PostTable.contextType eq PostContextType.COMMUNITY)
                    } else {
                        base and (PostTable.contextType neq PostContextType.COMMUNITY)
                    }
                }
                .orderBy(sortOrder, CommentTable.id to SortOrder.DESC)
                .limit(limit)

            CommentDao.wrapRows(query).toList()
        } else {
            CommentDao.find {
                (CommentTable.userId eq userId) and visibleToViewer(viewerId) and afterOp  // 활성 + 본인 블라인드 포함
            }
                .orderBy(sortOrder, CommentTable.id to SortOrder.DESC)
                .limit(limit)
                .toList()
        }
    }

    /** 댓글 일괄 조회 */
    override suspend fun findCommentsByIds(ids: Collection<Int>): List<CommentDao> {
        if (ids.isEmpty()) return emptyList()  // 빈 입력은 빈 목록
        return CommentDao.find {
            (CommentTable.id inList ids) and (CommentTable.isActive eq true)
        }.toList()
    }

    /** 삭제·블라인드 포함 댓글 일괄 조회 */
    override suspend fun findCommentsByIdsWithDeleted(ids: Collection<Int>): List<CommentDao> {
        if (ids.isEmpty()) return emptyList()
        return CommentDao.find {
            CommentTable.id inList ids  // 삭제·블라인드 포함
        }.toList()
    }

    /** 부모별 답글 일괄 조회 */
    override suspend fun findRepliesByParentIds(parentIds: Collection<Int>): List<CommentDao> {
        if (parentIds.isEmpty()) return emptyList()
        return CommentDao.find {
            (CommentTable.parentCommentId inList parentIds) and shownInThread()
        }
            .orderBy(CommentTable.createdAt to SortOrder.ASC, CommentTable.id to SortOrder.DESC)  // 오름차순
            .toList()
    }

    /** 포스트의 댓글 수 */
    override suspend fun countCommentsByPostId(postId: Int, viewerId: Int?): Int {
        return CommentDao.find {
            (CommentTable.postId eq postId) and visibleInThread(viewerId)  // 답글 포함
        }.count().toInt()
    }

    /** 댓글의 답글 수 */
    override suspend fun countRepliesByCommentId(commentId: Int, viewerId: Int?): Int {
        return CommentDao.find {
            (CommentTable.parentCommentId eq commentId) and visibleInThread(viewerId)
        }.count().toInt()
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

/** 활성 댓글과 삭제 자리 */
private fun SqlExpressionBuilder.shownInThread(): Op<Boolean> =
    (CommentTable.isActive eq true) or (CommentTable.isDeleted eq true)

/** 댓글·답글 목록용, 삭제 자리 포함 */
private fun SqlExpressionBuilder.visibleInThread(viewerId: Int?): Op<Boolean> =
    visibleToViewer(viewerId) or (CommentTable.isDeleted eq true)
