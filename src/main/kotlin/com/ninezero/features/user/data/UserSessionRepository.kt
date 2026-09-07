package com.ninezero.features.user.data

import com.ninezero.core.database.entities.user.UserSessionDao

interface UserSessionRepository {

    // 세션 생성
    suspend fun createSession(
        userId: Int,
        sessionId: String,
        userAgent: String,
        ipAddress: String,
        deviceType: String,
        browser: String,
        os: String,
        deviceId: String?,
        location: String?
    ): UserSessionDao

    // 세션 조회
    suspend fun findSessionById(sessionId: String): UserSessionDao?
    suspend fun findUserSessions(userId: Int): List<UserSessionDao>
    suspend fun findActiveByDevice(userId: Int, deviceId: String): UserSessionDao?

    // 세션 수정
    suspend fun updateLocation(sessionId: String, location: String?)
    suspend fun updateSessionOnLogin(
        sessionId: String,
        userAgent: String,
        ipAddress: String,
        deviceType: String,
        browser: String,
        os: String
    )

    // 세션 삭제
    suspend fun deleteSession(sessionId: String): Boolean
    suspend fun deleteUserSession(userId: Int, sessionId: String): Boolean
    suspend fun deleteOldSessions(userId: Int, keepCount: Int): List<String>
}
