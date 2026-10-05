package com.ninezero.features.upload.presentation.models.response

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class UploadPrepareResponse(
    val uploadId: Int,
    val uploadUrl: String,
    val contentType: String,  // 업로드 요청에 그대로 넣을 Content-Type
    val expiresAt: LocalDateTime
)
