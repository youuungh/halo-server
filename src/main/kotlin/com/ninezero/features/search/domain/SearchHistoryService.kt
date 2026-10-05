package com.ninezero.features.search.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.features.search.data.SearchHistoryRepository
import com.ninezero.features.search.presentation.models.response.SearchHistoryListResponse
import com.ninezero.features.search.toSearchHistoryResponse

class SearchHistoryService(
    private val searchHistoryRepository: SearchHistoryRepository
) {

    /** 검색 결과가 먼저라 저장 실패는 삼킴 */
    suspend fun saveHistory(
        userId: Int,
        keyword: String,
        searchType: String,
        resultCount: Int
    ) {
        try {
            query {
                searchHistoryRepository.createHistory(
                    userId = userId,
                    keyword = keyword,
                    searchType = searchType,
                    resultCount = resultCount
                )
            }
        } catch (_: Exception) {
        }
    }

    suspend fun getHistory(
        userId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): SearchHistoryListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val histories = searchHistoryRepository.findHistories(userId, validPage, validLimit)
            val totalCount = searchHistoryRepository.countHistories(userId).toInt()

            val historyResponses = histories.map { it.toSearchHistoryResponse() }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(historyResponses, pagination)
        }
    }

    suspend fun deleteHistory(userId: Int, historyId: Int) {
        query {
            val history = searchHistoryRepository.findHistoryById(historyId)
                ?: throw NotFoundException(Errors.Search.SEARCH_HISTORY_NOT_FOUND)

            if (history.userId != userId) {
                throw ForbiddenException(Errors.Search.SEARCH_HISTORY_DELETE_PERMISSION_DENIED)
            }

            searchHistoryRepository.deleteHistory(userId, historyId)
        }
    }

    suspend fun clearHistory(userId: Int) {
        query {
            searchHistoryRepository.clearHistories(userId)  // 지울 기록이 없어도 성공
        }
    }
}
