package com.ninezero.features.search.presentation.models.response

import com.ninezero.core.common.util.PaginatedResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class SearchHistoryResponse(
    val id: Int,
    val keyword: String,
    val searchType: String,
    val resultCount: Int,
    val createdAt: LocalDateTime
)

typealias SearchHistoryListResponse = PaginatedResponse<SearchHistoryResponse>
