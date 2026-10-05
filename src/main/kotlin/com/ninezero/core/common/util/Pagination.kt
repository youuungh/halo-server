package com.ninezero.core.common.util

import com.ninezero.core.common.config.Constants
import kotlinx.serialization.Serializable
import kotlin.math.ceil

// 페이지네이션 응답

data class PaginationInfo(
    val page: Int,
    val limit: Int,
    val totalCount: Int
) {
    val totalPages: Int = ceil(totalCount.toDouble() / limit).toInt()
    val hasNext: Boolean = page < totalPages
    val hasPrevious: Boolean = page > 1

    init {
        require(page > 0) { "page는 0보다 커야 합니다" }
        require(limit > 0) { "limit는 0보다 커야 합니다" }
        require(totalCount >= 0) { "totalCount는 음수일 수 없습니다" }
    }
}

@Serializable
data class PaginatedResponse<T>(
    val items: List<T>,
    val totalCount: Int,
    val page: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

fun <T> createPagedResponse(
    items: List<T>,
    pagination: PaginationInfo
): PaginatedResponse<T> {
    return PaginatedResponse(
        items = items,
        totalCount = pagination.totalCount,
        page = pagination.page,
        totalPages = pagination.totalPages,
        hasNext = pagination.hasNext,
        hasPrevious = pagination.hasPrevious
    )
}

// 페이지네이션 파라미터 보정

fun validatePaginationParams(page: Int, limit: Int): Pair<Int, Int> {
    val validPage = maxOf(1, page)
    val validLimit = when {
        limit <= 0 -> Constants.DEFAULT_PAGE_LIMIT
        limit > Constants.MAX_PAGE_LIMIT -> Constants.MAX_PAGE_LIMIT
        else -> limit
    }
    return validPage to validLimit
}

// 페이지 오프셋

fun Int.toOffset(limit: Int): Long = ((this - 1) * limit).toLong()
