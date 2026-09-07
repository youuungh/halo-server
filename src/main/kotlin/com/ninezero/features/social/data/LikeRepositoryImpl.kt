package com.ninezero.features.social.data

import com.ninezero.core.common.config.LikeType
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.social.LikeDao
import com.ninezero.core.database.entities.social.LikeTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and

class LikeRepositoryImpl : LikeRepository {

    /** 포스트 좋아요 생성 또는 재활성화 */
    override suspend fun createPostLike(userId: Int, postId: Int): LikeDao? {
        val existingLike = LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetId eq postId) and
                    (LikeTable.targetType eq LikeType.POST)
        }.firstOrNull()

        return when {
            existingLike == null -> {
                LikeDao.new {
                    this.userId = userId
                    this.targetId = postId
                    this.targetType = LikeType.POST
                }
            }
            !existingLike.isActive -> {
                existingLike.isActive = true
                existingLike
            }
            else -> null  // 이미 활성이면 null
        }
    }

    /** 댓글 좋아요 생성 또는 재활성화 */
    override suspend fun createCommentLike(userId: Int, commentId: Int): LikeDao? {
        val existingLike = LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetId eq commentId) and
                    (LikeTable.targetType eq LikeType.COMMENT)
        }.firstOrNull()

        return when {
            existingLike == null -> {
                LikeDao.new {
                    this.userId = userId
                    this.targetId = commentId
                    this.targetType = LikeType.COMMENT
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
    override suspend fun deleteLike(userId: Int, targetId: Int, targetType: LikeType): Boolean {
        val like = LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetId eq targetId) and
                    (LikeTable.targetType eq targetType) and
                    (LikeTable.isActive eq true)
        }.firstOrNull() ?: return false

        like.isActive = false
        return true
    }

    /** 포스트 좋아요 존재 여부 */
    override suspend fun isPostLiked(userId: Int, postId: Int): Boolean {
        return LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetId eq postId) and
                    (LikeTable.targetType eq LikeType.POST) and
                    (LikeTable.isActive eq true)
        }.count() > 0
    }

    /** 댓글 좋아요 존재 여부 */
    override suspend fun isCommentLiked(userId: Int, commentId: Int): Boolean {
        return LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetId eq commentId) and
                    (LikeTable.targetType eq LikeType.COMMENT) and
                    (LikeTable.isActive eq true)
        }.count() > 0
    }

    /** postId별 좋아요 여부 일괄 조회 */
    override suspend fun checkMultiplePostLikes(
        userId: Int,
        postIds: List<Int>
    ): Map<Int, Boolean> {
        if (postIds.isEmpty()) return emptyMap()

        val likedPostIds = LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetId inList postIds) and
                    (LikeTable.targetType eq LikeType.POST) and
                    (LikeTable.isActive eq true)
        }.map { it.targetId }.toSet()

        return postIds.associateWith { it in likedPostIds }
    }

    /** commentId별 좋아요 여부 일괄 조회 */
    override suspend fun checkMultipleCommentLikes(
        userId: Int,
        commentIds: List<Int>
    ): Map<Int, Boolean> {
        if (commentIds.isEmpty()) return emptyMap()

        val likedCommentIds = LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetId inList commentIds) and
                    (LikeTable.targetType eq LikeType.COMMENT) and
                    (LikeTable.isActive eq true)
        }.map { it.targetId }.toSet()

        return commentIds.associateWith { it in likedCommentIds }
    }

    /** 포스트의 좋아요 목록 */
    override suspend fun findPostLikes(postId: Int, page: Int, limit: Int): List<LikeDao> {
        return LikeDao.find {
            (LikeTable.targetId eq postId) and
                    (LikeTable.targetType eq LikeType.POST) and
                    (LikeTable.isActive eq true)
        }
            .orderBy(LikeTable.createdAt to SortOrder.DESC, LikeTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 댓글의 좋아요 목록 */
    override suspend fun findCommentLikes(commentId: Int, page: Int, limit: Int): List<LikeDao> {
        return LikeDao.find {
            (LikeTable.targetId eq commentId) and
                    (LikeTable.targetType eq LikeType.COMMENT) and
                    (LikeTable.isActive eq true)
        }
            .orderBy(LikeTable.createdAt to SortOrder.DESC, LikeTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 유저가 좋아요한 postId 목록 */
    override suspend fun findUserLikedPosts(userId: Int, page: Int, limit: Int): List<Int> {
        return LikeDao.find {
            (LikeTable.userId eq userId) and
                    (LikeTable.targetType eq LikeType.POST) and
                    (LikeTable.isActive eq true)
        }
            .orderBy(LikeTable.createdAt to SortOrder.DESC, LikeTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .map { it.targetId }
    }

    /** 유저의 좋아요 목록 */
    override suspend fun findUserLikes(userId: Int, page: Int, limit: Int, targetType: LikeType?): List<LikeDao> {
        return LikeDao.find {
            val baseCondition = (LikeTable.userId eq userId) and (LikeTable.isActive eq true)
            if (targetType != null) {  // targetType null이면 전체
                baseCondition and (LikeTable.targetType eq targetType)
            } else {
                baseCondition
            }
        }
            .orderBy(LikeTable.createdAt to SortOrder.DESC, LikeTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 유저의 좋아요 수 */
    override suspend fun countUserLikes(userId: Int, targetType: LikeType?): Int {
        return LikeDao.find {
            val baseCondition = (LikeTable.userId eq userId) and (LikeTable.isActive eq true)
            if (targetType != null) {
                baseCondition and (LikeTable.targetType eq targetType)
            } else {
                baseCondition
            }
        }.count().toInt()
    }

    /** 포스트의 좋아요 수 */
    override suspend fun countPostLikes(postId: Int): Int {
        return LikeDao.find {
            (LikeTable.targetId eq postId) and
                    (LikeTable.targetType eq LikeType.POST) and
                    (LikeTable.isActive eq true)
        }.count().toInt()
    }

    /** 댓글의 좋아요 수 */
    override suspend fun countCommentLikes(commentId: Int): Int {
        return LikeDao.find {
            (LikeTable.targetId eq commentId) and
                    (LikeTable.targetType eq LikeType.COMMENT) and
                    (LikeTable.isActive eq true)
        }.count().toInt()
    }
}
