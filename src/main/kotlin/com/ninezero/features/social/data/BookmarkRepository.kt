package com.ninezero.features.social.data

import com.ninezero.core.common.config.BookmarkTargetType
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
        targetIds: List<Int>
    ): Map<Int, Boolean>

    suspend fun findUserBookmarks(
        userId: Int,
        page: Int,
        limit: Int,
        targetType: BookmarkTargetType? = null
    ): List<BookmarkDao>

    // 카운트
    suspend fun countUserBookmarks(userId: Int, targetType: BookmarkTargetType? = null): Int
}
