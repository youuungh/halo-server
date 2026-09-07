package com.ninezero.features.social.data

import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.social.HiddenPostDao
import com.ninezero.core.database.entities.social.HiddenPostTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and

class HiddenPostRepositoryImpl : HiddenPostRepository {

    /** 포스트 숨김 생성 또는 재활성화 */
    override suspend fun createHiddenPost(userId: Int, postId: Int): HiddenPostDao? {
        val existingHiddenPost = HiddenPostDao.find {
            (HiddenPostTable.userId eq userId) and (HiddenPostTable.postId eq postId)
        }.firstOrNull()

        return when {
            existingHiddenPost == null -> {
                HiddenPostDao.new {
                    this.userId = userId
                    this.postId = postId
                }
            }
            !existingHiddenPost.isActive -> {
                existingHiddenPost.isActive = true
                existingHiddenPost
            }
            else -> null  // 이미 활성이면 null
        }
    }

    /** 포스트 숨김 해제 */
    override suspend fun deleteHiddenPost(userId: Int, postId: Int): Boolean {
        val hiddenPost = HiddenPostDao.find {
            (HiddenPostTable.userId eq userId) and
                    (HiddenPostTable.postId eq postId) and
                    (HiddenPostTable.isActive eq true)
        }.firstOrNull() ?: return false

        hiddenPost.isActive = false
        return true
    }

    /** 숨김 존재 여부 */
    override suspend fun isHidden(userId: Int, postId: Int): Boolean {
        return HiddenPostDao.find {
            (HiddenPostTable.userId eq userId) and
                    (HiddenPostTable.postId eq postId) and
                    (HiddenPostTable.isActive eq true)
        }.count() > 0
    }

    /** 유저가 숨긴 포스트 id 전체 */
    override suspend fun findHiddenPostIds(userId: Int): List<Int> {
        return HiddenPostDao.find {
            (HiddenPostTable.userId eq userId) and (HiddenPostTable.isActive eq true)
        }.map { it.postId }  // 피드 필터링용
    }

    /** 유저가 숨긴 포스트 id 목록 */
    override suspend fun findUserHiddenPosts(userId: Int, page: Int, limit: Int): List<Int> {
        return HiddenPostDao.find {
            (HiddenPostTable.userId eq userId) and (HiddenPostTable.isActive eq true)
        }
            .orderBy(HiddenPostTable.createdAt to SortOrder.DESC, HiddenPostTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .map { it.postId }
    }

    /** 유저가 숨긴 포스트 수 */
    override suspend fun countUserHiddenPosts(userId: Int): Int {
        return HiddenPostDao.find {
            (HiddenPostTable.userId eq userId) and (HiddenPostTable.isActive eq true)
        }.count().toInt()
    }
}
