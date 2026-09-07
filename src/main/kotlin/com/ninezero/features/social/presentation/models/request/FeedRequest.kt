package com.ninezero.features.social.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class FeedRequest(
    val page: Int = 1,
    val limit: Int = 20,
    val lastPostId: Int? = null
)
