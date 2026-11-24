package com.ninezero.features.user.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.security.JwtConfig
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.user.data.RefreshTokenRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.data.UserSessionRepository
import com.ninezero.features.user.presentation.models.response.AuthResponse
import com.ninezero.features.user.presentation.models.response.DeviceListResponse
import com.ninezero.features.user.toDeviceResponse
import com.ninezero.features.user.toUserResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class UserSessionService(
    private val userSessionRepository: UserSessionRepository,
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val webSocketManager: WebSocketManager,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

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

    /** 로그인 공통 세션 수립 */
    suspend fun establishSession(
        userId: Int,
        deviceId: String?,
        fid: String?,
        userAgent: String,
        ipAddress: String
    ): AuthResponse {
        val deviceInfo = UserAgentParser.parse(userAgent)  // 호출 전 자격 검증 완료 전제

        val (response, sessionId, removedSessionIds) = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            // 같은 기기면 세션 재사용
            val existingSession = deviceId?.let {
                userSessionRepository.findByDevice(user.id.value, it)
            }
            val sessionId = if (existingSession != null) {
                userSessionRepository.updateSessionOnLogin(
                    sessionId = existingSession.sessionId,
                    userAgent = userAgent,
                    ipAddress = ipAddress,
                    deviceType = deviceInfo.deviceType,
                    browser = deviceInfo.browser,
                    os = deviceInfo.os
                )
                // 이전 refresh token 무효화
                refreshTokenRepository.revokeBySession(existingSession.sessionId)
                existingSession.sessionId
            } else {
                val newSessionId = UUID.randomUUID().toString()
                userSessionRepository.createSession(
                    userId = user.id.value,
                    sessionId = newSessionId,
                    userAgent = userAgent,
                    ipAddress = ipAddress,
                    deviceType = deviceInfo.deviceType,
                    browser = deviceInfo.browser,
                    os = deviceInfo.os,
                    deviceId = deviceId
                )
                newSessionId
            }

            // 오래된 세션 정리 + 밀려난 세션 토큰 무효화
            val removedSessions = userSessionRepository.deleteOldSessions(
                userId = user.id.value,
                keepCount = Constants.User.MAX_ACTIVE_SESSIONS
            )
            removedSessions.forEach { removed ->
                refreshTokenRepository.revokeBySession(removed.sessionId)
                removed.deviceId?.let { userRepository.deleteDeviceToken(user.id.value, it) }  // 밀려난 기기로 푸시가 가지 않게
            }
            val removedSessionIds = removedSessions.map { it.sessionId }

            // 소유 이전으로 알림 누수 방지
            if (fid != null && deviceId != null) {
                userRepository.registerDeviceToken(user.id.value, deviceId, fid)
            }

            val (accessToken, refreshToken) = issueTokens(user, sessionId)

            // 비동기 location 갱신용
            Triple(
                AuthResponse(
                    user = user.toUserResponse(),
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresIn = Constants.User.ACCESS_TOKEN_VALIDITY_MINUTES * 60L
                ),
                sessionId,
                removedSessionIds
            )
        }

        // 커밋 뒤 소켓 종료
        removedSessionIds.forEach { webSocketManager.disconnectSession(it) }

        // location은 백그라운드
        coroutineScope.launch {
            try {
                val location = withTimeoutOrNull(3.seconds) {
                    IpLocationClient.getLocationFromIp(ipAddress)
                }
                if (location != null) {
                    query { userSessionRepository.updateLocation(sessionId, location) }
                }
            } catch (e: Exception) {
                logger.warn("세션 location 업데이트 실패 - sessionId: $sessionId, error: ${e.message}")
            }
        }

        return response
    }

    /** 로그인·토큰 재발급 공용 발급 */
    suspend fun issueTokens(
        user: UserDao,
        sessionId: String
    ): Pair<String, String> {
        val accessToken = JwtConfig.makeAccessToken(
            userId = user.id.value,
            email = user.email,
            username = user.username,
            role = user.role.name,
            sessionId = sessionId
        )

        val refreshToken = JwtConfig.makeRefreshToken()
        val refreshExpiresAt = Clock.System.now()
            .plus(Constants.User.REFRESH_TOKEN_VALIDITY_DAYS.days)
            .toLocalDateTime(TimeZone.UTC)
        refreshTokenRepository.create(
            userId = user.id.value,
            sessionId = sessionId,
            token = refreshToken,
            expiresAt = refreshExpiresAt
        )

        return accessToken to refreshToken
    }

    suspend fun clearDeviceTokenForSession(sessionId: String) {
        val session = userSessionRepository.findSessionById(sessionId) ?: return  // 세션 무효화 시 호출
        session.deviceId?.let { deviceId ->
            userRepository.deleteDeviceToken(session.userId, deviceId)
        }
    }

    /** 남길 세션 외 기기의 토큰·세션·푸시 토큰 정리, null이면 전 기기 */
    suspend fun signOutDevices(userId: Int, keepSessionId: String?) {
        if (keepSessionId == null) {
            refreshTokenRepository.revokeAllByUser(userId)
            userSessionRepository.deleteAllByUser(userId)
            userRepository.deleteAllDeviceTokensForUser(userId)
        } else {
            refreshTokenRepository.revokeAllByUserExcept(userId, keepSessionId)
            userSessionRepository.deleteAllByUserExcept(userId, keepSessionId).forEach { removed ->
                removed.deviceId?.let { userRepository.deleteDeviceToken(userId, it) }
            }
        }
    }
}
