package com.ninezero.features.social.presentation.models.response

import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostStatus
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.features.commerce.presentation.models.response.ProductSummaryResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class PostResponse(
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
    val isLiked: Boolean = false,
    val isBookmarked: Boolean = false,
    val isPinned: Boolean = false,
    val isHidden: Boolean = false,
    val isBlocked: Boolean = false,
    val productInfo: ProductSummaryResponse? = null,
    val requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE,
    val isSecret: Boolean = false,
    val canAccess: Boolean = true,
    val isBlinded: Boolean = false,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
