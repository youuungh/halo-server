package com.ninezero.features.admin.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class LogEntryResponse(
    val id: String,
    val time: LocalDateTime,
    val level: String,
    val source: String,  // 로거 이름
    val message: String,
    val stack: String?,  // 예외 스택
    val request: LogRequestResponse?  // HTTP 요청 줄만
)
