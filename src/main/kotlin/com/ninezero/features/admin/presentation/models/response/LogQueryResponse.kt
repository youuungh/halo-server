package com.ninezero.features.admin.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class LogQueryResponse(
    val entries: List<LogEntryResponse>,  // 최신순
    val counts: LogLevelCountsResponse,  // 수준 필터는 빼고 셈
    val histogram: List<LogBucketResponse>,  // 기간을 60칸으로 나눔
    val sources: List<LogSourceResponse>,
    val truncated: Boolean  // 파일이 기간보다 짧아 앞부분이 없음
)
