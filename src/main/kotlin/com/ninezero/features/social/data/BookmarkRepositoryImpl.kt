package com.ninezero.features.social.data

import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.social.BookmarkDao
import com.ninezero.core.database.entities.social.BookmarkTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and

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
        return BookmarkDao.find {
            (BookmarkTable.userId eq userId) and
                    (BookmarkTable.targetType eq targetType) and
                    (BookmarkTable.targetId eq targetId) and
                    (BookmarkTable.isActive eq true)
        }.count() > 0
    }

    /** targetId별 북마크 여부 일괄 조회 */
    override suspend fun checkMultipleBookmarks(
        userId: Int,
        targetType: BookmarkTargetType,
        targetIds: List<Int>
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
        page: Int,
        limit: Int,
        targetType: BookmarkTargetType?
    ): List<BookmarkDao> {
        return BookmarkDao.find {
            val baseCondition = (BookmarkTable.userId eq userId) and (BookmarkTable.isActive eq true)
            if (targetType != null) {  // targetType null이면 전체
                baseCondition and (BookmarkTable.targetType eq targetType)
            } else {
                baseCondition
            }
        }
            .orderBy(BookmarkTable.createdAt to SortOrder.DESC, BookmarkTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 유저의 북마크 수 */
    override suspend fun countUserBookmarks(userId: Int, targetType: BookmarkTargetType?): Int {
        return BookmarkDao.find {
            val baseCondition = (BookmarkTable.userId eq userId) and (BookmarkTable.isActive eq true)
            if (targetType != null) {
                baseCondition and (BookmarkTable.targetType eq targetType)
            } else {
                baseCondition
            }
        }.count().toInt()
    }
}
