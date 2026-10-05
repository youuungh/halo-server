package com.ninezero.features.upload.presentation.models.response

import com.ninezero.core.common.config.MediaDraftStatus
import com.ninezero.core.common.config.MediaType
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class MediaDraftResponse(
    val id: Int,
    val type: MediaType,
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    /** 잠금 콘텐츠 블러 프리뷰 URL */
    val previewUrl: String? = null,
    val status: MediaDraftStatus,  // 영상은 가공이 끝나야 READY
    val expiresAt: LocalDateTime
)
