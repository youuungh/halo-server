package com.ninezero.features.share.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class SharePostRequest(
    val postId: Int,
    val recipientUserIds: List<Int>,
    val message: String? = null
)
