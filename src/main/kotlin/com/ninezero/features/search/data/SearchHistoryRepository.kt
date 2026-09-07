package com.ninezero.features.search.data

import com.ninezero.core.database.entities.search.SearchHistoryDao

interface SearchHistoryRepository {

    // 검색 기록 생성/삭제
    suspend fun createHistory(
        userId: Int,
        keyword: String,
        searchType: String,
        resultCount: Int
    ): SearchHistoryDao

    suspend fun deleteHistory(userId: Int, historyId: Int): Boolean
    suspend fun clearHistories(userId: Int): Boolean

    // 검색 기록 조회
    suspend fun findHistoryById(historyId: Int): SearchHistoryDao?
    suspend fun findHistories(
        userId: Int,
        page: Int,
        limit: Int
    ): List<SearchHistoryDao>

    // 카운트
    suspend fun countHistories(userId: Int): Long
}
