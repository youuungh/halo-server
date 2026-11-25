package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class RoomArchivedEvent(
    val roomId: Int,
    val archived: Boolean
)
