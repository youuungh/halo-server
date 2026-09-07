package com.ninezero.features.social.presentation.models.response

import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostStatus
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class AdminPostResponse(
    val id: Int,
    val author: UserSummaryResponse,
    val content: String,
    val postType: PostType,
    val contextType: PostContextType,
    val status: PostStatus,
    val mediaItems: List<MediaItemResponse> = emptyList(),
    val tags: List<String> = emptyList(),
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val viewCount: Int = 0,
    val shareCount: Int = 0,
    val requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE,
    val isActive: Boolean,
    val isSecret: Boolean = false,
    val isPinnedInFeed: Boolean = false,
    val isPinnedInCommunity: Boolean = false,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
