package com.ninezero.core.websocket

import kotlinx.serialization.Serializable

/** 실시간 프레임 공통 봉투 */
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
    const val NOTIFICATION = "NOTIFICATION"
    const val CONNECTED = "CONNECTED"
    const val ERROR = "ERROR"
}
