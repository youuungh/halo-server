package com.ninezero.features.social.presentation.models.response

import com.ninezero.core.common.config.FeedType
import kotlinx.serialization.Serializable

@Serializable
data class FeedResponse(
    val posts: List<PostResponse>,
    val totalCount: Int,
    val page: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val lastPostId: Int? = null,
    val feedType: FeedType
)
