package com.ninezero.features.social.presentation.models.response

import com.ninezero.core.common.config.LikeType
import com.ninezero.core.common.util.CursorResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class LikedItemResponse(
    val targetType: LikeType,
    val targetId: Int,
    val createdAt: LocalDateTime,
    val post: PostResponse? = null,
    val comment: CommentResponse? = null
)

typealias LikedListResponse = CursorResponse<LikedItemResponse>
