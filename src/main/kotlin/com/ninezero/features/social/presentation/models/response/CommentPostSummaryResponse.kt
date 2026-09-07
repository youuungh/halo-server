package com.ninezero.features.social.presentation.models.response

import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class CommentPostSummaryResponse(
    val id: Int,
    val contextType: PostContextType,
    val excerpt: String,
    val mediaItems: List<MediaItemResponse> = emptyList(),
    val author: UserSummaryResponse,
    val creator: UserSummaryResponse? = null,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val isLiked: Boolean = false,
    val isBookmarked: Boolean = false,
    val isDeleted: Boolean = false,
    val isHidden: Boolean = false,  // 크리에이터 해제로 숨겨진 글, 삭제와 구분
    val requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE,
    val isSecret: Boolean = false,
    val canAccess: Boolean = true,
    val createdAt: LocalDateTime
)
