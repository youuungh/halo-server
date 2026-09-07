package com.ninezero.features.user.data

import com.ninezero.core.database.entities.user.RefreshTokenDao
import com.ninezero.core.database.entities.user.RefreshTokenTable
import com.ninezero.core.security.TokenHasher
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update

class RefreshTokenRepositoryImpl : RefreshTokenRepository {

    /** refresh token 발급 */
    override suspend fun create(
        userId: Int,
        sessionId: String,
        token: String,
        expiresAt: LocalDateTime
    ): RefreshTokenDao {
        return RefreshTokenDao.new {
            this.userId = userId
            this.sessionId = sessionId
            this.token = TokenHasher.hash(token)  // 평문 아닌 해시만 저장
            this.expiresAt = expiresAt
            this.isRevoked = false
        }
    }

    /** 평문 토큰으로 해시 조회 */
    override suspend fun findByToken(token: String): RefreshTokenDao? {
        val hashed = TokenHasher.hash(token)
        return RefreshTokenDao.find { RefreshTokenTable.token eq hashed }  // 만료 여부는 미확인
            .firstOrNull()
    }

    /** 평문 토큰으로 해시 무효화 */
    override suspend fun revokeByToken(token: String): Boolean {
        val hashed = TokenHasher.hash(token)
        val updated = RefreshTokenTable.update({
            (RefreshTokenTable.token eq hashed) and (RefreshTokenTable.isRevoked eq false)
        }) {
            it[isRevoked] = true
        }
        return updated > 0
    }

    /** 세션의 미무효화 토큰 전부 무효화 */
    override suspend fun revokeBySession(sessionId: String): Int {
        return RefreshTokenTable.update({
            (RefreshTokenTable.sessionId eq sessionId) and (RefreshTokenTable.isRevoked eq false)
        }) {
            it[isRevoked] = true
        }
    }

    /** 유저의 미무효화 토큰 전부 무효화 */
    override suspend fun revokeAllByUser(userId: Int): Int {
        return RefreshTokenTable.update({
            (RefreshTokenTable.userId eq userId) and (RefreshTokenTable.isRevoked eq false)
        }) {
            it[isRevoked] = true
        }
    }

    /** 현재 세션 제외 토큰 무효화 */
    override suspend fun revokeAllByUserExcept(userId: Int, exceptSessionId: String): Int {
        return RefreshTokenTable.update({
            (RefreshTokenTable.userId eq userId) and
                    (RefreshTokenTable.isRevoked eq false) and
                    (RefreshTokenTable.sessionId neq exceptSessionId)
        }) {
            it[isRevoked] = true
        }
    }

    /** 옛 refresh token 회전 처리 */
    override suspend fun markRotated(token: String, rotatedAt: LocalDateTime): Boolean {
        val hashed = TokenHasher.hash(token)
        val updated = RefreshTokenTable.update({
            (RefreshTokenTable.token eq hashed) and (RefreshTokenTable.isRevoked eq false)
        }) {
            it[isRevoked] = true
            it[RefreshTokenTable.rotatedAt] = rotatedAt
        }
        return updated > 0
    }

    /** 만료 토큰 삭제 */
    override suspend fun deleteExpired(now: LocalDateTime): Int {
        return RefreshTokenTable.deleteWhere { expiresAt less now }
    }
}
