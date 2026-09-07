package com.ninezero.features.search.data

import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.util.ilike
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.social.FollowTable
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList

class CreatorSearchRepositoryImpl : CreatorSearchRepository {

    /** 크리에이터 검색 */
    override suspend fun findCreatorsByKeyword(
        keyword: String,
        page: Int,
        limit: Int,
        excludeUserIds: Set<Int>
    ): List<UserDao> {
        val searchPattern = "%${keyword}%"

        var condition: Op<Boolean> = (UserTable.role eq UserRole.CREATOR) and
                (UserTable.isActive eq true) and
                (UserTable.username ilike searchPattern)

        if (excludeUserIds.isNotEmpty()) {
            condition = condition and (UserTable.id notInList excludeUserIds)
        }

        return UserDao.find { condition }
            .orderBy(UserTable.createdAt to SortOrder.DESC, UserTable.id to SortOrder.DESC)  // 최신 가입순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 인기 크리에이터 조회 */
    override suspend fun getPopularCreators(limit: Int, excludeUserIds: Set<Int>): List<UserDao> {
        val popularCreatorIds = FollowTable
            .select(FollowTable.followingId)
            .where { FollowTable.isActive eq true }
            .groupBy(FollowTable.followingId)
            .orderBy(FollowTable.followingId.count(), SortOrder.DESC)
            .orderBy(FollowTable.followingId, SortOrder.DESC)
            .limit(limit)
            .map { it[FollowTable.followingId] }

        if (popularCreatorIds.isEmpty()) {
            return emptyList()
        }

        val orderMap = popularCreatorIds.withIndex().associate { it.value to it.index }

        var condition: Op<Boolean> = (UserTable.id inList popularCreatorIds) and
                (UserTable.role eq UserRole.CREATOR) and
                (UserTable.isActive eq true)

        if (excludeUserIds.isNotEmpty()) {
            condition = condition and (UserTable.id notInList excludeUserIds)
        }

        // 필터가 인기 선정 뒤 적용돼 limit 미달 가능
        val creators = UserDao.find { condition }.toList()

        return creators.sortedBy { orderMap[it.id.value] }  // 팔로우 수 순서 유지
    }

    /** 크리에이터 검색 결과 수 */
    override suspend fun countCreatorsByKeyword(keyword: String, excludeUserIds: Set<Int>): Long {
        val searchPattern = "%${keyword}%"

        var condition: Op<Boolean> = (UserTable.role eq UserRole.CREATOR) and
                (UserTable.isActive eq true) and
                (UserTable.username ilike searchPattern)

        if (excludeUserIds.isNotEmpty()) {
            condition = condition and (UserTable.id notInList excludeUserIds)
        }

        return UserDao.find { condition }.count()
    }
}
