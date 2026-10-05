package com.ninezero.features.user.data

import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.database.entities.user.UserSessionDao
import com.ninezero.core.database.entities.user.UserSessionTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere

class UserSessionRepositoryImpl : UserSessionRepository {

    /** 세션 생성 */
    override suspend fun createSession(
        userId: Int,
        sessionId: String,
        userAgent: String,
        ipAddress: String,
        deviceType: String,
        browser: String,
        os: String,
        deviceId: String?
    ): UserSessionDao {
        return UserSessionDao.new {
            this.userId = userId
            this.sessionId = sessionId
            this.userAgent = userAgent
            this.ipAddress = ipAddress
            this.deviceType = deviceType
            this.browser = browser
            this.os = os
            this.deviceId = deviceId
            this.lastActive = nowUtc()
        }
    }

    /** 세션 조회 */
    override suspend fun findSessionById(sessionId: String): UserSessionDao? {
        return UserSessionDao.find { UserSessionTable.sessionId eq sessionId }
            .firstOrNull()
    }

    /** 유저의 세션 전체 목록 조회 */
    override suspend fun findUserSessions(userId: Int): List<UserSessionDao> {
        return UserSessionDao.find { UserSessionTable.userId eq userId }
            .orderBy(UserSessionTable.lastActive to SortOrder.DESC, UserSessionTable.id to SortOrder.DESC)  // 최신순
            .toList()
    }

    /** 기기의 기존 세션 조회 */
    override suspend fun findByDevice(userId: Int, deviceId: String): UserSessionDao? {
        return UserSessionDao.find {
            (UserSessionTable.userId eq userId) and (UserSessionTable.deviceId eq deviceId)
        }.firstOrNull()
    }

    /** 세션의 location 갱신 */
    override suspend fun updateLocation(sessionId: String, location: String?) {
        UserSessionDao.find { UserSessionTable.sessionId eq sessionId }
            .firstOrNull()
            ?.apply { this.location = location }  // 세션 없으면 무시
    }

    /** 재로그인 시 같은 기기 세션 갱신 */
    override suspend fun updateSessionOnLogin(
        sessionId: String,
        userAgent: String,
        ipAddress: String,
        deviceType: String,
        browser: String,
        os: String
    ) {
        UserSessionDao.find { UserSessionTable.sessionId eq sessionId }
            .firstOrNull()
            ?.apply {  // 세션 없으면 무시
                this.userAgent = userAgent
                this.ipAddress = ipAddress
                this.deviceType = deviceType
                this.browser = browser
                this.os = os
                this.lastActive = nowUtc()
            }
    }

    /** 토큰 재발급 때 마지막 활동 시각 갱신 */
    override suspend fun updateLastActive(sessionId: String) {
        UserSessionDao.find { UserSessionTable.sessionId eq sessionId }
            .firstOrNull()
            ?.apply { lastActive = nowUtc() }
    }

    /** 본인 세션 삭제 */
    override suspend fun deleteUserSession(userId: Int, sessionId: String): Boolean {
        return UserSessionTable.deleteWhere {
            (UserSessionTable.userId eq userId) and (UserSessionTable.sessionId eq sessionId)
        } > 0
    }

    /** 최신 keepCount개만 남기고 세션 삭제 */
    override suspend fun deleteOldSessions(userId: Int, keepCount: Int): List<RemovedSession> {
        val sessions = UserSessionDao.find { UserSessionTable.userId eq userId }
            .orderBy(UserSessionTable.lastActive to SortOrder.DESC, UserSessionTable.id to SortOrder.DESC)
            .toList()

        if (sessions.size <= keepCount) return emptyList()

        val toDelete = sessions.drop(keepCount)
        val removed = toDelete.map { RemovedSession(it.sessionId, it.deviceId) }
        toDelete.forEach { it.delete() }
        return removed
    }

    /** 남길 세션 외 전부 삭제 */
    override suspend fun deleteAllByUserExcept(userId: Int, exceptSessionId: String): List<RemovedSession> {
        val toDelete = UserSessionDao.find {
            (UserSessionTable.userId eq userId) and (UserSessionTable.sessionId neq exceptSessionId)
        }.toList()
        val removed = toDelete.map { RemovedSession(it.sessionId, it.deviceId) }
        toDelete.forEach { it.delete() }
        return removed
    }

    /** 유저 세션 전부 삭제 */
    override suspend fun deleteAllByUser(userId: Int): Int {
        return UserSessionTable.deleteWhere { UserSessionTable.userId eq userId }
    }
}
