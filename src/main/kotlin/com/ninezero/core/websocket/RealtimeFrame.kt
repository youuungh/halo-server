package com.ninezero.core.websocket

import kotlinx.serialization.Serializable

@Serializable
data class RealtimeFrame<T>(
    val type: String,
    val data: T? = null,
    val message: String? = null
)

object RealtimeFrameType {
    const val MESSAGE = "MESSAGE"
    const val MESSAGE_READ = "MESSAGE_READ"
    const val MESSAGE_DELETED = "MESSAGE_DELETED"
    const val ROOM_READ = "ROOM_READ"
    const val ROOM_ARCHIVED = "ROOM_ARCHIVED"
    const val ROOM_CLEARED = "ROOM_CLEARED"
    const val NOTIFICATION = "NOTIFICATION"
    const val CONNECTED = "CONNECTED"
    const val ERROR = "ERROR"
}
