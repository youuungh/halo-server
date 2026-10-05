package com.ninezero.features.user.domain

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.features.user.data.UserSessionRepository
import com.ninezero.features.user.presentation.models.response.DeviceListResponse
import com.ninezero.features.user.toDeviceResponse

class UserSessionService(
    private val userSessionRepository: UserSessionRepository
) {

    suspend fun getUserDevices(userId: Int, currentSessionId: String): DeviceListResponse {
        return query {
            val sessions = userSessionRepository.findUserSessions(userId)

            val devices = sessions.map { it.toDeviceResponse(currentSessionId) }

            DeviceListResponse(devices = devices)
        }
    }

    suspend fun requireDeviceOwner(userId: Int, sessionId: String) {
        val owned = query {
            userSessionRepository.findSessionById(sessionId)?.userId == userId
        }

        if (!owned) {
            throw NotFoundException(Errors.User.SESSION_NOT_FOUND)
        }
    }
}
