package com.ninezero.features.social.presentation.models.response

import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class ThreadCommentResponse(
    val id: Int,
    val content: String,
    val mediaItems: List<MediaItemResponse> = emptyList(),
    val author: UserSummaryResponse,
    val replyTo: UserSummaryResponse?,
    val createdAt: LocalDateTime,
    val likeCount: Int,
    val replyCount: Int,
    val isLiked: Boolean,
    val isBookmarked: Boolean = false,
    val isProfileUser: Boolean,
    val isBlocked: Boolean = false,
    val isDeleted: Boolean = false,
    val isBlinded: Boolean = false
)
