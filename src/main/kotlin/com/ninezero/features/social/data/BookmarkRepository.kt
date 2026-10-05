package com.ninezero.features.social.data

import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.database.entities.social.BookmarkDao

interface BookmarkRepository {

    // 북마크 생성/삭제
    suspend fun createBookmark(userId: Int, targetType: BookmarkTargetType, targetId: Int): BookmarkDao?
    suspend fun deleteBookmark(userId: Int, targetType: BookmarkTargetType, targetId: Int): Boolean

    // 북마크 조회
    suspend fun isBookmarked(userId: Int, targetType: BookmarkTargetType, targetId: Int): Boolean
    suspend fun checkMultipleBookmarks(
        userId: Int,
        targetType: BookmarkTargetType,
        targetIds: Collection<Int>
    ): Map<Int, Boolean>

    suspend fun findUserBookmarks(
        userId: Int,
        cursor: PageCursor?,
        limit: Int,
        targetType: BookmarkTargetType?,
        excludedAuthorIds: Set<Int>
    ): List<BookmarkDao>
}
