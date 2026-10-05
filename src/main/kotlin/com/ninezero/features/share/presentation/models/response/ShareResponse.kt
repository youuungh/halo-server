package com.ninezero.features.share.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class ShareResponse(
    val sharedRoomIds: List<Int>,
    val recipientCount: Int
)
