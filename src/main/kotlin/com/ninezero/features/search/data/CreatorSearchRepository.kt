package com.ninezero.features.search.data

import com.ninezero.core.database.entities.user.UserDao

interface CreatorSearchRepository {

    // 크리에이터 검색
    suspend fun findCreatorsByKeyword(
        keyword: String,
        page: Int,
        limit: Int,
        excludeUserIds: Set<Int> = emptySet()
    ): List<UserDao>

    suspend fun getPopularCreators(limit: Int, excludeUserIds: Set<Int> = emptySet()): List<UserDao>

    // 카운트
    suspend fun countCreatorsByKeyword(keyword: String, excludeUserIds: Set<Int> = emptySet()): Long
}
