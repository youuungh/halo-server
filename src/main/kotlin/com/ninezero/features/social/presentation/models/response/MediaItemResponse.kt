package com.ninezero.features.social.presentation.models.response

import com.ninezero.core.common.config.MediaType
import kotlinx.serialization.Serializable

@Serializable
data class MediaItemResponse(
    val id: Int,
    val type: MediaType,
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    /** 잠금 콘텐츠 블러 프리뷰 URL */
    val previewUrl: String? = null,
    val sortOrder: Int
)
