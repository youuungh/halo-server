package com.ninezero.features.social.presentation.models.request

import com.ninezero.core.common.config.PostType
import kotlinx.serialization.Serializable

@Serializable
data class CommunityPostRequest(
    val content: String,
    val postType: PostType = PostType.TEXT,
    val isSecret: Boolean = false  // 비밀글
)
