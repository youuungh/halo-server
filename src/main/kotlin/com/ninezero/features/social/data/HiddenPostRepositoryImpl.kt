package com.ninezero.features.social.data

import com.ninezero.core.database.entities.social.HiddenPostDao
import com.ninezero.core.database.entities.social.HiddenPostTable
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

    /** 유저가 숨긴 포스트 id 전체 */
    override suspend fun findHiddenPostIds(userId: Int): List<Int> {
        return HiddenPostDao.find {
            (HiddenPostTable.userId eq userId) and (HiddenPostTable.isActive eq true)
        }.map { it.postId }  // 피드 필터링용
    }
}
