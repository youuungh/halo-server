package com.ninezero.features.social.data

import com.ninezero.core.common.config.LikeType
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.afterNewest
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.social.CommentTable
import com.ninezero.core.database.entities.social.LikeDao
import com.ninezero.core.database.entities.social.LikeTable
import com.ninezero.core.database.entities.social.PostTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or

class LikeRepositoryImpl : LikeRepository {

    /** 좋아요 생성 또는 재활성화 */
    override suspend fun createLike(userId: Int, targetType: LikeType, targetId: Int): LikeDao? {
        val existingLike = LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetType eq targetType) and
                    (LikeTable.targetId eq targetId)
        }.firstOrNull()

        return when {
            existingLike == null -> {
                LikeDao.new {
                    this.userId = userId
                    this.targetType = targetType
                    this.targetId = targetId
                }
            }
            !existingLike.isActive -> {
                existingLike.isActive = true
                existingLike
            }
            else -> null  // 이미 활성이면 null
        }
    }

    /** 좋아요 삭제 */
    override suspend fun deleteLike(userId: Int, targetType: LikeType, targetId: Int): Boolean {
        val like = LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetType eq targetType) and
                    (LikeTable.targetId eq targetId) and
                    (LikeTable.isActive eq true)
        }.firstOrNull() ?: return false

        like.isActive = false
        return true
    }

    /** 좋아요 존재 여부 */
    override suspend fun isLiked(userId: Int, targetType: LikeType, targetId: Int): Boolean {
        return !LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetType eq targetType) and
                    (LikeTable.targetId eq targetId) and
                    (LikeTable.isActive eq true)
        }.empty()
    }

    /** targetId별 좋아요 여부 일괄 조회 */
    override suspend fun checkMultipleLikes(
        userId: Int,
        targetType: LikeType,
        targetIds: Collection<Int>
    ): Map<Int, Boolean> {
        if (targetIds.isEmpty()) return emptyMap()

        val likedIds = LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetType eq targetType) and
                    (LikeTable.targetId inList targetIds) and
                    (LikeTable.isActive eq true)
        }.map { it.targetId }.toSet()

        return targetIds.associateWith { it in likedIds }
    }

    /** 대상의 좋아요 목록 */
    override suspend fun findTargetLikes(
        targetType: LikeType,
        targetId: Int,
        page: Int,
        limit: Int,
        excludedUserIds: Set<Int>
    ): List<LikeDao> {
        return LikeDao.find { targetLikesCondition(targetType, targetId, excludedUserIds) }
            .orderBy(LikeTable.createdAt to SortOrder.DESC, LikeTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 유저의 좋아요 목록 */
    override suspend fun findUserLikes(
        userId: Int,
        cursor: PageCursor?,
        limit: Int,
        targetType: LikeType?,
        excludedAuthorIds: Set<Int>
    ): List<LikeDao> {
        return LikeDao.find {
            val afterOp = cursor?.let { afterNewest(LikeTable.createdAt, LikeTable.id, it) } ?: Op.TRUE
            userLikesCondition(userId, targetType, excludedAuthorIds) and afterOp
        }
            .orderBy(LikeTable.createdAt to SortOrder.DESC, LikeTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .toList()
    }

    /** 대상의 좋아요 수 */
    override suspend fun countTargetLikes(targetType: LikeType, targetId: Int, excludedUserIds: Set<Int>): Int {
        return LikeDao.find { targetLikesCondition(targetType, targetId, excludedUserIds) }.count().toInt()
    }

    /** 대상 좋아요 목록·수 공통 조건 */
    private fun SqlExpressionBuilder.targetLikesCondition(
        targetType: LikeType,
        targetId: Int,
        excludedUserIds: Set<Int>
    ): Op<Boolean> = (LikeTable.targetType eq targetType) and
            (LikeTable.targetId eq targetId) and
            (LikeTable.isActive eq true) and
            (LikeTable.userId notInList excludedUserIds)

    /** 유저 좋아요 목록 조건 */
    private fun SqlExpressionBuilder.userLikesCondition(
        userId: Int,
        targetType: LikeType?,
        excludedAuthorIds: Set<Int>
    ): Op<Boolean> {
        // 삭제·블라인드 대상과 제외 작성자 대상은 목록에서 제외
        val visiblePosts = PostTable.select(PostTable.id).where {
            (PostTable.isActive eq true) and (PostTable.userId notInList excludedAuthorIds)
        }
        val visibleComments = CommentTable.select(CommentTable.id).where {
            (CommentTable.isActive eq true) and (CommentTable.userId notInList excludedAuthorIds)
        }
        var condition = (LikeTable.userId eq userId) and (LikeTable.isActive eq true) and (
            ((LikeTable.targetType eq LikeType.POST) and (LikeTable.targetId inSubQuery visiblePosts)) or
                    ((LikeTable.targetType eq LikeType.COMMENT) and (LikeTable.targetId inSubQuery visibleComments))
        )
        if (targetType != null) {  // targetType null이면 전체
            condition = condition and (LikeTable.targetType eq targetType)
        }
        return condition
    }
}
