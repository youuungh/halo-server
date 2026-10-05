package com.ninezero.features.social.presentation.models.request

import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import kotlinx.serialization.Serializable

@Serializable
data class PostRequest(
    val content: String,
    val postType: PostType = PostType.TEXT,
    val productId: Int? = null,
    val requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE,
    val tagIds: List<Int>? = null,
    val sectionTagId: Int? = null,
    val mediaDraftIds: List<Int> = emptyList(),  // 표시 순서대로
    val clientRequestId: String? = null  // 같은 요청이 반복되면 만든 글을 그대로 응답
)
