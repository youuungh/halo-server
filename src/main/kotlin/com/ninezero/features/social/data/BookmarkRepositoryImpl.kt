package com.ninezero.features.social.data

import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.afterNewest
import com.ninezero.core.database.entities.social.BookmarkDao
import com.ninezero.core.database.entities.social.BookmarkTable
import com.ninezero.core.database.entities.social.CommentTable
import com.ninezero.core.database.entities.social.PostTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or

class BookmarkRepositoryImpl : BookmarkRepository {

    /** 북마크 생성 또는 재활성화 */
    override suspend fun createBookmark(userId: Int, targetType: BookmarkTargetType, targetId: Int): BookmarkDao? {
        val existingBookmark = BookmarkDao.find {
            (BookmarkTable.userId eq userId) and
                    (BookmarkTable.targetType eq targetType) and
                    (BookmarkTable.targetId eq targetId)
        }.firstOrNull()

        return when {
            existingBookmark == null -> {
                BookmarkDao.new {
                    this.userId = userId
                    this.targetType = targetType
                    this.targetId = targetId
                }
            }
            !existingBookmark.isActive -> {
                existingBookmark.isActive = true
                existingBookmark
            }
            else -> null  // 이미 활성이면 null
        }
    }

    /** 북마크 삭제 */
    override suspend fun deleteBookmark(userId: Int, targetType: BookmarkTargetType, targetId: Int): Boolean {
        val bookmark = BookmarkDao.find {
            (BookmarkTable.userId eq userId) and
                    (BookmarkTable.targetType eq targetType) and
                    (BookmarkTable.targetId eq targetId) and
                    (BookmarkTable.isActive eq true)
        }.firstOrNull() ?: return false

        bookmark.isActive = false
        return true
    }

    /** 북마크 존재 여부 */
    override suspend fun isBookmarked(userId: Int, targetType: BookmarkTargetType, targetId: Int): Boolean {
        return !BookmarkDao.find {
            (BookmarkTable.userId eq userId) and
                    (BookmarkTable.targetType eq targetType) and
                    (BookmarkTable.targetId eq targetId) and
                    (BookmarkTable.isActive eq true)
        }.empty()
    }

    /** targetId별 북마크 여부 일괄 조회 */
    override suspend fun checkMultipleBookmarks(
        userId: Int,
        targetType: BookmarkTargetType,
        targetIds: Collection<Int>
    ): Map<Int, Boolean> {
        if (targetIds.isEmpty()) return emptyMap()

        val bookmarkedIds = BookmarkDao.find {
            (BookmarkTable.userId eq userId) and
                    (BookmarkTable.targetType eq targetType) and
                    (BookmarkTable.targetId inList targetIds) and
                    (BookmarkTable.isActive eq true)
        }.map { it.targetId }.toSet()

        return targetIds.associateWith { it in bookmarkedIds }
    }

    /** 유저의 북마크 목록 */
    override suspend fun findUserBookmarks(
        userId: Int,
        cursor: PageCursor?,
        limit: Int,
        targetType: BookmarkTargetType?,
        excludedAuthorIds: Set<Int>
    ): List<BookmarkDao> {
        return BookmarkDao.find {
            val afterOp = cursor?.let { afterNewest(BookmarkTable.createdAt, BookmarkTable.id, it) } ?: Op.TRUE
            userBookmarksCondition(userId, targetType, excludedAuthorIds) and afterOp
        }
            .orderBy(BookmarkTable.createdAt to SortOrder.DESC, BookmarkTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .toList()
    }

    /** 유저 북마크 목록 조건 */
    private fun SqlExpressionBuilder.userBookmarksCondition(
        userId: Int,
        targetType: BookmarkTargetType?,
        excludedAuthorIds: Set<Int>
    ): Op<Boolean> {
        // 삭제·블라인드 대상과 제외 작성자 대상은 목록에서 제외
        val visiblePosts = PostTable.select(PostTable.id).where {
            (PostTable.isActive eq true) and (PostTable.userId notInList excludedAuthorIds)
        }
        val visibleComments = CommentTable.select(CommentTable.id).where {
            (CommentTable.isActive eq true) and (CommentTable.userId notInList excludedAuthorIds)
        }
        var condition = (BookmarkTable.userId eq userId) and (BookmarkTable.isActive eq true) and (
            ((BookmarkTable.targetType eq BookmarkTargetType.POST) and (BookmarkTable.targetId inSubQuery visiblePosts)) or
                    ((BookmarkTable.targetType eq BookmarkTargetType.COMMENT) and (BookmarkTable.targetId inSubQuery visibleComments))
        )
        if (targetType != null) {  // targetType null이면 전체
            condition = condition and (BookmarkTable.targetType eq targetType)
        }
        return condition
    }
}
