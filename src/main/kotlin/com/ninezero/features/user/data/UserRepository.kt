package com.ninezero.features.user.data

import com.ninezero.core.common.config.UserRole
import com.ninezero.core.database.entities.user.PendingSignupDao
import com.ninezero.core.database.entities.user.UserDao
import kotlinx.datetime.LocalDateTime

data class UserWithStats(
    val user: UserDao,
    val followerCount: Int,
    val followingCount: Int,
    val postCount: Int
)

data class UserStatistics(
    val totalUsers: Int,
    val totalCreators: Int,
    val totalAdmins: Int,
    val newUsersToday: Int,
    val newUsersThisWeek: Int,
    val newUsersThisMonth: Int,
    val activeUsers: Int
)

interface UserRepository {

    // 사용자 생성
    suspend fun createUser(email: String, passwordHash: String?, username: String, role: UserRole = UserRole.USER): UserDao

    // 사용자 조회
    suspend fun findUserById(id: Int): UserDao?
    suspend fun findUserByEmail(email: String): UserDao?

    suspend fun findUsersByUsernames(usernames: List<String>): List<UserDao>
    suspend fun findUsersByIds(userIds: List<Int>): List<UserDao>

    suspend fun findAllUsers(
        page: Int,
        limit: Int,
        role: UserRole? = null,
        search: String? = null,
        sortBy: String? = null,
        sortOrder: String? = null,
        excludeUserIds: Set<Int> = emptySet()
    ): List<UserWithStats>

    suspend fun searchUsersByUsername(query: String, page: Int, limit: Int, excludeUserIds: Set<Int> = emptySet()): List<UserWithStats>

    // 유효성 검사
    suspend fun existsByEmail(email: String): Boolean
    suspend fun existsByUsername(username: String): Boolean

    // 카운트
    suspend fun countAllUsers(role: UserRole? = null, search: String? = null, excludeUserIds: Set<Int> = emptySet()): Int
    suspend fun countSearchResults(query: String, excludeUserIds: Set<Int> = emptySet()): Int

    // 대기 가입
    suspend fun findPendingSignupByEmail(email: String): PendingSignupDao?
    suspend fun upsertPendingSignup(
        email: String,
        username: String,
        passwordHash: String,
        codeHash: String,
        codeExpiry: LocalDateTime,
        expiresAt: LocalDateTime,
        sentCount: Int,
        lastSentAt: LocalDateTime
    ): Boolean
    suspend fun incrementPendingSignupAttempts(id: Int): Boolean
    suspend fun deletePendingSignup(id: Int): Boolean

    // 비밀번호 재설정 토큰
    suspend fun setPasswordResetToken(userId: Int, token: String?, expiry: LocalDateTime?): Boolean
    suspend fun findByPasswordResetToken(token: String): UserDao?
    suspend fun clearPasswordResetToken(userId: Int): Boolean

    // 비밀번호 재설정 코드
    suspend fun savePasswordResetCode(userId: Int, codeHash: String, expiry: LocalDateTime, sentCount: Int, lastSentAt: LocalDateTime): Boolean
    suspend fun incrementPasswordResetCodeAttempts(userId: Int): Boolean
    suspend fun clearPasswordResetCode(userId: Int): Boolean

    // 비밀번호 수정
    suspend fun updatePassword(userId: Int, passwordHash: String): Boolean

    // FCM 기기 토큰
    suspend fun registerDeviceToken(userId: Int, deviceId: String, fid: String): Boolean
    suspend fun deleteDeviceToken(deviceId: String): Boolean
    suspend fun deleteAllDeviceTokensForUser(userId: Int): Int
    suspend fun deleteDeviceTokensByFids(fids: List<String>): Int
    suspend fun getDeviceFidsForUser(userId: Int): List<String>
    suspend fun getDeviceFidsForUsers(userIds: List<Int>): List<String>
    suspend fun deleteStaleDeviceTokens(before: LocalDateTime): Int

    // 관리자
    suspend fun promoteToCreator(userId: Int)
    suspend fun demoteCreator(userId: Int)
    suspend fun countUsersByRole(role: UserRole): Int

    // 통계
    suspend fun getUserStatistics(
        todayStart: LocalDateTime,
        todayEnd: LocalDateTime,
        weekStart: LocalDateTime,
        monthStart: LocalDateTime
    ): UserStatistics
}
