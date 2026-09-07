package com.ninezero.features.search.data

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.search.SearchHistoryTable
import com.ninezero.core.database.entities.search.SearchHistoryDao
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere

class SearchHistoryRepositoryImpl : SearchHistoryRepository {

    /** 검색 기록 생성 */
    override suspend fun createHistory(
        userId: Int,
        keyword: String,
        searchType: String,
        resultCount: Int
    ): SearchHistoryDao {
        SearchHistoryDao.find {
            (SearchHistoryTable.userId eq userId) and
                    (SearchHistoryTable.keyword eq keyword)
        }.firstOrNull()?.delete()  // createdAt 갱신용 삭제 후 재삽입

        // 위에서 지운 뒤라 재검색은 정원을 소모하지 않음
        val currentCount = SearchHistoryDao.find {
            SearchHistoryTable.userId eq userId
        }.count()

        if (currentCount >= Constants.Search.MAX_SEARCH_HISTORY) {
            val oldestHistory = SearchHistoryDao.find {
                SearchHistoryTable.userId eq userId
            }
                .orderBy(SearchHistoryTable.createdAt to SortOrder.ASC, SearchHistoryTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()

            oldestHistory?.delete()  // 정원 초과 시 가장 오래된 기록 삭제
        }

        return SearchHistoryDao.new {
            this.userId = userId
            this.keyword = keyword
            this.searchType = searchType
            this.resultCount = resultCount
        }
    }

    /** 본인 검색 기록 삭제 */
    override suspend fun deleteHistory(userId: Int, historyId: Int): Boolean {
        val history = SearchHistoryDao.find {
            (SearchHistoryTable.id eq historyId) and (SearchHistoryTable.userId eq userId)
        }.firstOrNull()

        history?.delete()
        return history != null
    }

    /** 유저의 검색 기록 전부 삭제 */
    override suspend fun clearHistories(userId: Int): Boolean {
        val deleted = SearchHistoryTable.deleteWhere {
            this.userId eq userId
        }
        return deleted > 0
    }

    /** 검색 기록 조회 */
    override suspend fun findHistoryById(historyId: Int): SearchHistoryDao? {
        return SearchHistoryDao.findById(historyId)
    }

    /** 유저의 검색 기록 목록 조회 */
    override suspend fun findHistories(
        userId: Int,
        page: Int,
        limit: Int
    ): List<SearchHistoryDao> {
        return SearchHistoryDao.find {
            SearchHistoryTable.userId eq userId
        }
            .orderBy(SearchHistoryTable.createdAt to SortOrder.DESC, SearchHistoryTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 유저의 검색 기록 수 */
    override suspend fun countHistories(userId: Int): Long {
        return SearchHistoryDao.find {
            SearchHistoryTable.userId eq userId
        }.count()
    }
}
