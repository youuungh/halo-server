package com.ninezero.core.database.entities.search

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object SearchHistoryTable : BaseIntIdTable("search_histories") {
    val userId = integer("user_id").references(UserTable.id)
    val keyword = varchar("keyword", 100)
    val searchType = varchar("search_type", 20).default("CREATOR")
    val resultCount = integer("result_count").default(0)

    init {
        index(false, userId, createdAt)         // 사용자별 검색 기록 조회 최적화
        index(false, keyword)                   // 인기 검색어 집계 최적화
        index(false, searchType, createdAt)     // 검색 타입별 조회 최적화
    }
}

class SearchHistoryDao(id: EntityID<Int>) : BaseIntEntity(id, SearchHistoryTable) {
    companion object : BaseIntEntityClass<SearchHistoryDao>(SearchHistoryTable)

    var userId by SearchHistoryTable.userId
    var keyword by SearchHistoryTable.keyword
    var searchType by SearchHistoryTable.searchType
    var resultCount by SearchHistoryTable.resultCount
}
