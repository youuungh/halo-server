package com.ninezero.features.user.domain

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.user.data.UserSessionRepository
import com.ninezero.features.user.presentation.models.response.DeviceListResponse
import com.ninezero.features.user.toDeviceResponse

class UserSessionService(
    private val userSessionRepository: UserSessionRepository,
    private val webSocketManager: WebSocketManager
) {

    suspend fun getUserDevices(userId: Int, currentSessionId: String): DeviceListResponse {
        return query {
            val sessions = userSessionRepository.findUserSessions(userId)

            val devices = sessions.map { session ->
                session.toDeviceResponse(currentSessionId)
            }

            DeviceListResponse(
                devices = devices,
                totalCount = devices.size
            )
        }
    }

    suspend fun revokeDevice(userId: Int, sessionId: String) {
        val deleted = query {
            userSessionRepository.deleteUserSession(userId, sessionId)
        }

        if (!deleted) {
            throw NotFoundException(Errors.User.SESSION_NOT_FOUND)
        }
        webSocketManager.disconnectSession(sessionId)  // 세션 삭제 후 소켓 종료
    }
}