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
    val sectionTagId: Int? = null
)
