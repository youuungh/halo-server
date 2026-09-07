package com.ninezero.features.social.presentation.models.response

import com.ninezero.features.user.presentation.models.response.UserPreviewResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class CommentResponse(
    val id: Int,
    val postId: Int,
    val author: UserSummaryResponse,
    val content: String,
    val mediaItems: List<MediaItemResponse> = emptyList(),
    val parentCommentId: Int? = null,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val isLiked: Boolean = false,
    val isBookmarked: Boolean = false,
    val isBlocked: Boolean = false,
    val isBlinded: Boolean = false,
    val firstReply: CommentResponse? = null,
    val hasMoreReplies: Boolean = false,
    val moreReplyCount: Int = 0,
    val replyPreviewUsers: List<UserPreviewResponse> = emptyList(),
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
