package com.ninezero.features.user.data

import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.afterNewest
import com.ninezero.core.database.entities.user.BlockedUserDao
import com.ninezero.core.database.entities.user.BlockedUserTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class BlockedUserRepositoryImpl : BlockedUserRepository {

    /** 차단 생성 또는 재활성화 */
    override suspend fun createBlock(userId: Int, blockedUserId: Int): BlockedUserDao? {
        val existingBlock = BlockedUserDao.find {
            (BlockedUserTable.userId eq userId) and (BlockedUserTable.blockedUserId eq blockedUserId)
        }.firstOrNull()

        return when {
            existingBlock == null -> {
                BlockedUserDao.new {
                    this.userId = userId
                    this.blockedUserId = blockedUserId
                }
            }
            !existingBlock.isActive -> {
                existingBlock.isActive = true
                existingBlock
            }
            else -> null  // 이미 차단됨
        }
    }

    /** 차단 해제 */
    override suspend fun deleteBlock(userId: Int, blockedUserId: Int): Boolean {
        val block = BlockedUserDao.find {
            (BlockedUserTable.userId eq userId) and
                    (BlockedUserTable.blockedUserId eq blockedUserId) and
                    (BlockedUserTable.isActive eq true)
        }.firstOrNull() ?: return false

        block.isActive = false
        return true
    }

    /** 탈퇴 정리용 차단 양방향 비활성화 */
    override suspend fun deleteAllInvolvingUser(userId: Int): Int {
        return BlockedUserTable.update({
            ((BlockedUserTable.userId eq userId) or (BlockedUserTable.blockedUserId eq userId)) and
                    (BlockedUserTable.isActive eq true)
        }) {
            it[isActive] = false  // 상대 차단 목록에서도 소멸
        }
    }

    /** 단방향 차단 확인 */
    override suspend fun isBlocked(userId: Int, blockedUserId: Int): Boolean {
        return !BlockedUserDao.find {
            (BlockedUserTable.userId eq userId) and
                    (BlockedUserTable.blockedUserId eq blockedUserId) and
                    (BlockedUserTable.isActive eq true)
        }.empty()
    }

    /** 양방향 차단 확인 */
    override suspend fun isBlockedEither(userId1: Int, userId2: Int): Boolean {
        return !BlockedUserDao.find {
            (BlockedUserTable.isActive eq true) and (
                ((BlockedUserTable.userId eq userId1) and (BlockedUserTable.blockedUserId eq userId2)) or
                ((BlockedUserTable.userId eq userId2) and (BlockedUserTable.blockedUserId eq userId1))
            )
        }.empty()
    }

    /** 차단 관계 유저 ID 집합 조회 */
    override suspend fun findBlockRelatedUserIds(userId: Int): Set<Int> {
        return BlockedUserTable.selectAll()
            .where {
                (BlockedUserTable.isActive eq true) and (
                    (BlockedUserTable.userId eq userId) or (BlockedUserTable.blockedUserId eq userId)
                )
            }
            .map { row ->
                val blockerId = row[BlockedUserTable.userId]
                val blockedId = row[BlockedUserTable.blockedUserId]
                if (blockerId == userId) blockedId else blockerId
            }
            .toSet()
    }

    /** 내 차단 목록 */
    override suspend fun findBlockedUsers(userId: Int, cursor: PageCursor?, limit: Int): List<BlockedUserDao> {
        return BlockedUserDao.find {
            val afterOp = cursor?.let { afterNewest(BlockedUserTable.createdAt, BlockedUserTable.id, it) } ?: Op.TRUE
            (BlockedUserTable.userId eq userId) and (BlockedUserTable.isActive eq true) and afterOp
        }
            .orderBy(BlockedUserTable.createdAt to SortOrder.DESC, BlockedUserTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .toList()
    }
}
