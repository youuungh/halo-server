package com.ninezero.features.social.data

import com.ninezero.core.common.config.LikeType
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.database.entities.social.LikeDao

interface LikeRepository {

    // 좋아요 생성/삭제
    suspend fun createLike(userId: Int, targetType: LikeType, targetId: Int): LikeDao?
    suspend fun deleteLike(userId: Int, targetType: LikeType, targetId: Int): Boolean

    // 좋아요 조회
    suspend fun isLiked(userId: Int, targetType: LikeType, targetId: Int): Boolean
    suspend fun checkMultipleLikes(userId: Int, targetType: LikeType, targetIds: Collection<Int>): Map<Int, Boolean>
    suspend fun findTargetLikes(
        targetType: LikeType,
        targetId: Int,
        page: Int,
        limit: Int,
        excludedUserIds: Set<Int>
    ): List<LikeDao>

    suspend fun findUserLikes(
        userId: Int,
        cursor: PageCursor?,
        limit: Int,
        targetType: LikeType?,
        excludedAuthorIds: Set<Int>
    ): List<LikeDao>

    // 카운트
    suspend fun countTargetLikes(targetType: LikeType, targetId: Int, excludedUserIds: Set<Int>): Int
}
