package com.ninezero.features.user.data

import com.ninezero.core.database.entities.user.RefreshTokenDao
import kotlinx.datetime.LocalDateTime

interface RefreshTokenRepository {

    // 토큰 발급
    suspend fun create(
        userId: Int,
        sessionId: String,
        token: String,
        expiresAt: LocalDateTime
    ): RefreshTokenDao

    // 토큰 조회
    suspend fun findByToken(token: String): RefreshTokenDao?

    // 토큰 무효화
    suspend fun revokeByToken(token: String): Boolean
    suspend fun revokeBySession(sessionId: String): Int
    suspend fun revokeAllByUser(userId: Int): Int
    suspend fun revokeAllByUserExcept(userId: Int, exceptSessionId: String): Int

    // 토큰 회전
    suspend fun markRotated(token: String, rotatedAt: LocalDateTime): Boolean

    // 만료 토큰 삭제
    suspend fun deleteExpired(now: LocalDateTime): Int
}
