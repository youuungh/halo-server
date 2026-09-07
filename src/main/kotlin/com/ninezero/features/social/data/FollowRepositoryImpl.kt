package com.ninezero.features.social.data

import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.util.ilike
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.social.FollowDao
import com.ninezero.core.database.entities.social.FollowTable
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.sql.*

class FollowRepositoryImpl : FollowRepository {

    /** 팔로우 생성 또는 재활성화 */
    override suspend fun createFollow(followerId: Int, followingId: Int): FollowDao? {
        val existingFollow = FollowDao.find {
            (FollowTable.followerId eq followerId) and (FollowTable.followingId eq followingId)
        }.firstOrNull()

        return when {
            existingFollow == null -> {  // 벨 알림 기본 켜짐
                FollowDao.new {
                    this.followerId = followerId
                    this.followingId = followingId
                    this.notifyNewPost = true
                    this.notifyNewProduct = true
                }
            }
            !existingFollow.isActive -> {
                existingFollow.isActive = true
                existingFollow.notifyNewPost = true
                existingFollow.notifyNewProduct = true
                existingFollow
            }
            else -> null  // 이미 활성이면 null
        }
    }

    /** 팔로우 삭제 */
    override suspend fun deleteFollow(followerId: Int, followingId: Int): Boolean {
        val follow = FollowDao.find {
            (FollowTable.followerId eq followerId) and
                    (FollowTable.followingId eq followingId) and
                    (FollowTable.isActive eq true)
        }.firstOrNull() ?: return false

        follow.isActive = false
        return true
    }

    /** 탈퇴 정리용 팔로우 양방향 비활성화 */
    override suspend fun deleteAllInvolvingUser(userId: Int): Int {
        return FollowTable.update({
            ((FollowTable.followerId eq userId) or (FollowTable.followingId eq userId)) and
                    (FollowTable.isActive eq true)
        }) {
            it[isActive] = false  // 벨 알림 설정도 함께 무효화
        }
    }

    /** 팔로우 존재 여부 */
    override suspend fun isFollowing(followerId: Int, followingId: Int): Boolean {
        return FollowDao.find {
            (FollowTable.followerId eq followerId) and
                    (FollowTable.followingId eq followingId) and
                    (FollowTable.isActive eq true)
        }.count() > 0
    }

    /** 대상 유저별 팔로우 여부 일괄 조회 */
    override suspend fun checkMultipleFollowStatus(
        followerId: Int,
        targetUserIds: List<Int>
    ): Map<Int, Boolean> {
        if (targetUserIds.isEmpty()) return emptyMap()

        val followingSet = FollowDao.find {
            (FollowTable.followerId eq followerId) and
                    (FollowTable.followingId inList targetUserIds) and
                    (FollowTable.isActive eq true)
        }.map { it.followingId }.toSet()

        return targetUserIds.associateWith { it in followingSet }
    }

    /** 유저의 팔로워 목록 */
    override suspend fun findFollowers(userId: Int, page: Int, limit: Int): List<FollowDao> {
        return FollowDao.find {
            (FollowTable.followingId eq userId) and (FollowTable.isActive eq true)
        }
            .orderBy(FollowTable.createdAt to SortOrder.DESC, FollowTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 유저의 팔로잉 목록 */
    override suspend fun findFollowing(userId: Int, page: Int, limit: Int): List<FollowDao> {
        return FollowDao.find {
            (FollowTable.followerId eq userId) and (FollowTable.isActive eq true)
        }
            .orderBy(FollowTable.createdAt to SortOrder.DESC, FollowTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 유저가 팔로우하는 followingId 전체 */
    override suspend fun findFollowingIds(userId: Int): List<Int> {
        return FollowDao.find {
            (FollowTable.followerId eq userId) and (FollowTable.isActive eq true)
        }.map { it.followingId }
    }

    /** 유저별 followingId 일괄 조회 */
    override suspend fun findFollowingIdsByUserIds(userIds: List<Int>, limitPerUser: Int): Map<Int, List<Int>> {
        if (userIds.isEmpty()) return emptyMap()

        val allFollows = FollowDao.find {
            (FollowTable.followerId inList userIds) and (FollowTable.isActive eq true)
        }.toList()

        return userIds.associateWith { userId ->
            allFollows
                .filter { it.followerId == userId }
                .take(limitPerUser)
                .map { it.followingId }
        }
    }

    /** 유저의 팔로워 수 */
    override suspend fun countFollowers(userId: Int): Int {
        return FollowDao.find {
            (FollowTable.followingId eq userId) and (FollowTable.isActive eq true)
        }.count().toInt()
    }

    /** 유저의 팔로잉 수 */
    override suspend fun countFollowing(userId: Int): Int {
        return FollowDao.find {
            (FollowTable.followerId eq userId) and (FollowTable.isActive eq true)
        }.count().toInt()
    }

    /** 유저별 팔로워 수 일괄 조회 */
    override suspend fun countFollowersByUserIds(userIds: List<Int>): Map<Int, Int> {
        if (userIds.isEmpty()) return emptyMap()

        return FollowTable.select(FollowTable.followingId, FollowTable.followingId.count())
            .where {
                (FollowTable.followingId inList userIds) and
                        (FollowTable.isActive eq true)
            }
            .groupBy(FollowTable.followingId)  // 0인 유저는 키 없음
            .associate { it[FollowTable.followingId] to it[FollowTable.followingId.count()].toInt() }
    }

    /** 유저별 팔로잉 수 일괄 조회 */
    override suspend fun countFollowingByUserIds(userIds: List<Int>): Map<Int, Int> {
        if (userIds.isEmpty()) return emptyMap()

        return FollowTable.select(FollowTable.followerId, FollowTable.followerId.count())
            .where {
                (FollowTable.followerId inList userIds) and
                        (FollowTable.isActive eq true)
            }
            .groupBy(FollowTable.followerId)  // 0인 유저는 키 없음
            .associate { it[FollowTable.followerId] to it[FollowTable.followerId.count()].toInt() }
    }

    /** 새 글 알림 켠 팔로워 id 전체 */
    override suspend fun findPostNotifyFollowers(creatorId: Int): List<Int> {
        return FollowDao.find {
            (FollowTable.followingId eq creatorId) and
                    (FollowTable.isActive eq true) and
                    (FollowTable.notifyNewPost eq true)
        }.map { it.followerId }
    }

    /** 새 상품 알림 켠 팔로워 id 전체 */
    override suspend fun findProductNotifyFollowers(creatorId: Int): List<Int> {
        return FollowDao.find {
            (FollowTable.followingId eq creatorId) and
                    (FollowTable.isActive eq true) and
                    (FollowTable.notifyNewProduct eq true)
        }.map { it.followerId }
    }

    /** 팔로우 알림 설정 조회 */
    override suspend fun findNotifySettings(followerId: Int, followingId: Int): Pair<Boolean, Boolean>? {
        return FollowDao.find {
            (FollowTable.followerId eq followerId) and
                    (FollowTable.followingId eq followingId) and
                    (FollowTable.isActive eq true)
        }.firstOrNull()?.let { it.notifyNewPost to it.notifyNewProduct }  // 팔로우 중 아니면 null
    }

    /** 팔로우 중인 크리에이터의 팔로우 목록 */
    override suspend fun findFollowedCreators(userId: Int, page: Int, limit: Int): List<FollowDao> {
        return FollowDao.wrapRows(
            FollowTable
                .innerJoin(UserTable, { FollowTable.followingId }, { UserTable.id })
                .select(FollowTable.columns)
                .where {
                    (FollowTable.followerId eq userId) and
                            (FollowTable.isActive eq true) and
                            (UserTable.isActive eq true) and
                            (UserTable.role eq UserRole.CREATOR)  // 알림 대상이 크리에이터뿐이라 필터
                }
                .orderBy(FollowTable.createdAt to SortOrder.DESC, FollowTable.id to SortOrder.DESC)  // 최신순
                .limit(limit).offset(page.toOffset(limit))
        ).toList()
    }

    /** 팔로우 중인 크리에이터 수 */
    override suspend fun countFollowedCreators(userId: Int): Int {
        return FollowTable
            .innerJoin(UserTable, { FollowTable.followingId }, { UserTable.id })
            .select(FollowTable.id)
            .where {
                (FollowTable.followerId eq userId) and
                        (FollowTable.isActive eq true) and
                        (UserTable.isActive eq true) and
                        (UserTable.role eq UserRole.CREATOR)
            }
            .count().toInt()
    }

    /** 팔로우 새 글 알림 설정 */
    override suspend fun updateNewPostNotification(
        followerId: Int,
        followingId: Int,
        enabled: Boolean
    ): Boolean {
        val follow = FollowDao.find {
            (FollowTable.followerId eq followerId) and
                    (FollowTable.followingId eq followingId) and
                    (FollowTable.isActive eq true)
        }.firstOrNull() ?: return false  // 팔로우 중 아니면 false

        follow.notifyNewPost = enabled
        return true
    }

    /** 팔로우 새 상품 알림 설정 */
    override suspend fun updateNewProductNotification(
        followerId: Int,
        followingId: Int,
        enabled: Boolean
    ): Boolean {
        val follow = FollowDao.find {
            (FollowTable.followerId eq followerId) and
                    (FollowTable.followingId eq followingId) and
                    (FollowTable.isActive eq true)
        }.firstOrNull() ?: return false  // 팔로우 중 아니면 false

        follow.notifyNewProduct = enabled
        return true
    }

    /** 팔로워 검색 */
    override suspend fun findFollowersWithSearch(
        userId: Int,
        search: String,
        page: Int,
        limit: Int
    ): List<Int> {
        val searchPattern = "%$search%"

        return FollowTable
            .innerJoin(UserTable, { FollowTable.followerId }, { UserTable.id })
            .leftJoin(UserProfileTable, { UserTable.id }, { UserProfileTable.userId })
            .select(FollowTable.followerId)
            .where {
                (FollowTable.followingId eq userId) and
                        (FollowTable.isActive eq true) and
                        (UserTable.isActive eq true) and  // 비활성 유저 제외
                        ((UserTable.username ilike searchPattern) or (UserProfileTable.displayName ilike searchPattern))
            }
            .orderBy(FollowTable.createdAt to SortOrder.DESC, FollowTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .map { it[FollowTable.followerId] }
    }

    /** 팔로잉 검색 */
    override suspend fun findFollowingWithSearch(
        userId: Int,
        search: String,
        page: Int,
        limit: Int
    ): List<Int> {
        val searchPattern = "%$search%"

        return FollowTable
            .innerJoin(UserTable, { FollowTable.followingId }, { UserTable.id })
            .leftJoin(UserProfileTable, { UserTable.id }, { UserProfileTable.userId })
            .select(FollowTable.followingId)
            .where {
                (FollowTable.followerId eq userId) and
                        (FollowTable.isActive eq true) and
                        (UserTable.isActive eq true) and  // 비활성 유저 제외
                        ((UserTable.username ilike searchPattern) or (UserProfileTable.displayName ilike searchPattern))
            }
            .orderBy(FollowTable.createdAt to SortOrder.DESC, FollowTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .map { it[FollowTable.followingId] }
    }

    /** 팔로워 검색 결과 수 */
    override suspend fun countFollowersWithSearch(userId: Int, search: String): Int {
        val searchPattern = "%$search%"

        return FollowTable
            .innerJoin(UserTable, { FollowTable.followerId }, { UserTable.id })
            .leftJoin(UserProfileTable, { UserTable.id }, { UserProfileTable.userId })
            .select(FollowTable.followerId)
            .where {
                (FollowTable.followingId eq userId) and
                        (FollowTable.isActive eq true) and
                        (UserTable.isActive eq true) and
                        ((UserTable.username ilike searchPattern) or (UserProfileTable.displayName ilike searchPattern))
            }
            .count().toInt()
    }

    /** 팔로잉 검색 결과 수 */
    override suspend fun countFollowingWithSearch(userId: Int, search: String): Int {
        val searchPattern = "%$search%"

        return FollowTable
            .innerJoin(UserTable, { FollowTable.followingId }, { UserTable.id })
            .leftJoin(UserProfileTable, { UserTable.id }, { UserProfileTable.userId })
            .select(FollowTable.followingId)
            .where {
                (FollowTable.followerId eq userId) and
                        (FollowTable.isActive eq true) and
                        (UserTable.isActive eq true) and
                        ((UserTable.username ilike searchPattern) or (UserProfileTable.displayName ilike searchPattern))
            }
            .count().toInt()
    }
}
