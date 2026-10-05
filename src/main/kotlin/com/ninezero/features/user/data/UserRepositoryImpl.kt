package com.ninezero.features.user.data

import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.exception.ValidationException
import com.ninezero.core.common.util.containsLikePattern
import com.ninezero.core.common.util.ilike
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.social.FollowTable
import com.ninezero.core.database.entities.social.PostTable
import com.ninezero.core.database.entities.user.*
import com.ninezero.core.security.TokenHasher
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList

class UserRepositoryImpl : UserRepository {

    /** 사용자 목록 공통 조건 */
    private fun buildUserListCondition(
        role: UserRole?,
        search: String?,
        excludeUserIds: Set<Int>
    ): Op<Boolean> {
        var condition: Op<Boolean> = UserTable.isActive eq true

        role?.let {
            condition = condition and (UserTable.role eq it)
        }

        search?.let {
            condition = condition and (UserTable.username ilike containsLikePattern(it))
        }

        if (excludeUserIds.isNotEmpty()) {
            condition = condition and (UserTable.id notInList excludeUserIds)
        }

        return condition
    }

    /** 사용자 생성 */
    override suspend fun createUser(
        email: String,
        passwordHash: String?,
        username: String,
        role: UserRole
    ): UserDao {
        return UserDao.new {
            this.email = email
            this.passwordHash = passwordHash  // null이면 간편로그인 전용 계정
            this.username = username
            this.role = role
        }
    }

    /** 사용자 조회 */
    override suspend fun findUserById(id: Int): UserDao? {
        return UserDao.findById(id)  // 탈퇴 유저도 반환
    }

    /** email로 사용자 조회 */
    override suspend fun findUserByEmail(email: String): UserDao? {
        return UserDao.find { UserTable.email eq email }.firstOrNull()  // isActive 무관
    }

    /** username으로 사용자 일괄 조회 */
    override suspend fun findUsersByUsernames(usernames: List<String>): List<UserDao> {
        if (usernames.isEmpty()) return emptyList()
        return UserDao.find { UserTable.username inList usernames }.toList()
    }

    /** 사용자 일괄 조회 */
    override suspend fun findUsersByIds(userIds: Collection<Int>): List<UserDao> {
        if (userIds.isEmpty()) return emptyList()
        return UserDao.find { UserTable.id inList userIds }.with(UserDao::profile).toList()
    }

    /** 사용자 목록 조회 */
    override suspend fun findAllUsers(
        page: Int,
        limit: Int,
        role: UserRole?,
        search: String?,
        sortBy: String?,
        sortOrder: String?,
        excludeUserIds: Set<Int>
    ): List<UserWithStats> {
        val condition = buildUserListCondition(role, search, excludeUserIds)

        val order = if (sortOrder.equals("asc", ignoreCase = true)) SortOrder.ASC else SortOrder.DESC

        // 세 카운트를 한 쿼리에 조인하면 유저마다 팔로워×팔로잉×글 행이 생겨 페이지를 먼저 뽑음
        val sortColumn = when (sortBy?.lowercase()) {
            "followercount" -> activeCountSubquery(FollowTable.followingId, FollowTable.isActive)
            "followingcount" -> activeCountSubquery(FollowTable.followerId, FollowTable.isActive)
            "postcount" -> activeCountSubquery(PostTable.userId, PostTable.isActive)
            "username" -> UserTable.username
            "role" -> UserTable.role
            else -> UserTable.createdAt
        }

        val users = UserTable.selectAll()
            .where { condition }
            .orderBy(sortColumn to order, UserTable.id to SortOrder.DESC)
            .limit(limit)
            .offset(page.toOffset(limit))
            .map { UserDao.wrapRow(it) }
            .with(UserDao::profile)
        if (users.isEmpty()) return emptyList()

        val userIds = users.map { it.id.value }
        val followerCounts = countActiveByUser(FollowTable.followingId, FollowTable.isActive, userIds)
        val followingCounts = countActiveByUser(FollowTable.followerId, FollowTable.isActive, userIds)
        val postCounts = countActiveByUser(PostTable.userId, PostTable.isActive, userIds)

        return users.map { user ->
            UserWithStats(
                user = user,
                followerCount = followerCounts[user.id.value] ?: 0,
                followingCount = followingCounts[user.id.value] ?: 0,
                postCount = postCounts[user.id.value] ?: 0
            )
        }
    }

    /** 정렬용 유저별 활성 행 수 */
    private fun activeCountSubquery(userColumn: Column<Int>, activeColumn: Column<Boolean>): Expression<Long?> =
        wrapAsExpression(
            userColumn.table.select(userColumn.count())
                .where { EqOp(userColumn, UserTable.id) and (activeColumn eq true) }
        )

    /** 페이지 유저별 활성 행 수, 0인 유저는 키 없음 */
    private fun countActiveByUser(userColumn: Column<Int>, activeColumn: Column<Boolean>, userIds: Collection<Int>): Map<Int, Int> {
        val count = userColumn.count()
        return userColumn.table.select(userColumn, count)
            .where { (userColumn inList userIds) and (activeColumn eq true) }
            .groupBy(userColumn)
            .associate { it[userColumn] to it[count].toInt() }
    }

    /** 사용자 검색 */
    override suspend fun searchUsersByUsername(query: String, page: Int, limit: Int, excludeUserIds: Set<Int>): List<UserWithStats> {
        return findAllUsers(page, limit, role = null, search = query, excludeUserIds = excludeUserIds)
    }

    /** email 사용자 존재 여부 확인 */
    override suspend fun existsByEmail(email: String): Boolean {
        return !UserDao.find { UserTable.email eq email }.empty()
    }

    /** username 사용자 존재 여부 확인 */
    override suspend fun existsByUsername(username: String): Boolean {
        return !UserDao.find { UserTable.username eq username }.empty()
    }

    /** 활성 사용자 수 */
    override suspend fun countAllUsers(role: UserRole?, search: String?, excludeUserIds: Set<Int>): Int {
        return UserDao.find { buildUserListCondition(role, search, excludeUserIds) }.count().toInt()
    }

    /** 사용자 검색 결과 수 */
    override suspend fun countSearchResults(query: String, excludeUserIds: Set<Int>): Int {
        return countAllUsers(role = null, search = query, excludeUserIds = excludeUserIds)
    }

    /** email로 대기 가입 조회 */
    override suspend fun findPendingSignupByEmail(email: String): PendingSignupDao? {
        return PendingSignupDao.find { PendingSignupTable.email eq email }.firstOrNull()  // 만료 여부 무관
    }

    /** 대기 가입 저장 */
    override suspend fun upsertPendingSignup(
        email: String,
        username: String,
        passwordHash: String,
        codeHash: String,
        codeExpiry: LocalDateTime,
        expiresAt: LocalDateTime,
        sentCount: Int,
        lastSentAt: LocalDateTime
    ): Boolean {
        val existing = PendingSignupDao.find { PendingSignupTable.email eq email }.firstOrNull()
        val pending = existing ?: PendingSignupDao.new {
            this.email = email
        }
        pending.username = username
        pending.passwordHash = passwordHash
        pending.codeHash = codeHash
        pending.codeExpiry = codeExpiry
        pending.expiresAt = expiresAt
        pending.attempts = 0  // 새 코드 발송이므로 시도 횟수 초기화
        pending.sentCount = sentCount
        pending.lastSentAt = lastSentAt
        return true
    }

    /** 발송 실패한 대기 가입의 발송 횟수·시각 복원 */
    override suspend fun restorePendingSignupSend(email: String, sentCount: Int, lastSentAt: LocalDateTime?): Boolean {
        val pending = PendingSignupDao.find { PendingSignupTable.email eq email }.firstOrNull() ?: return false
        pending.sentCount = sentCount
        pending.lastSentAt = lastSentAt
        return true
    }

    /** 대기 가입의 코드 검증 시도 횟수 +1 */
    override suspend fun incrementPendingSignupAttempts(id: Int): Boolean {
        val pending = PendingSignupDao.findById(id) ?: return false
        pending.attempts += 1
        return true
    }

    /** 대기 가입 삭제 */
    override suspend fun deletePendingSignup(id: Int): Boolean {
        val pending = PendingSignupDao.findById(id) ?: return false
        pending.delete()
        return true
    }

    /** 만료 대기 가입 일괄 삭제 */
    override suspend fun deleteExpiredPendingSignups(before: LocalDateTime): Int {
        return PendingSignupTable.deleteWhere { PendingSignupTable.expiresAt less before }
    }

    /** 비밀번호 재설정 토큰과 만료 설정 */
    override suspend fun setPasswordResetToken(
        userId: Int,
        token: String,
        expiry: LocalDateTime
    ): Boolean {
        val user = UserDao.findById(userId) ?: return false
        user.passwordResetToken = TokenHasher.hash(token)  // 원문 대신 해시
        user.passwordResetTokenExpiry = expiry
        return true
    }

    /** 비밀번호 재설정 토큰으로 사용자 조회 */
    override suspend fun findByPasswordResetToken(token: String): UserDao? {
        return UserDao.find { UserTable.passwordResetToken eq TokenHasher.hash(token) }.firstOrNull()  // 만료 검사 없음
    }

    /** 비밀번호 재설정 토큰과 만료 제거 */
    override suspend fun clearPasswordResetToken(userId: Int): Boolean {
        val user = UserDao.findById(userId) ?: return false
        user.passwordResetToken = null
        user.passwordResetTokenExpiry = null
        return true
    }

    /** 비밀번호 재설정 코드 저장 */
    override suspend fun savePasswordResetCode(
        userId: Int,
        codeHash: String,
        expiry: LocalDateTime,
        sentCount: Int,
        lastSentAt: LocalDateTime
    ): Boolean {
        val user = UserDao.findById(userId) ?: return false
        user.passwordResetCode = codeHash
        user.passwordResetCodeExpiry = expiry
        user.passwordResetCodeAttempts = 0
        user.passwordResetCodeSentCount = sentCount
        user.passwordResetCodeLastSentAt = lastSentAt
        return true
    }

    /** 발송 실패한 재설정 코드의 발송 횟수·시각 복원 */
    override suspend fun restorePasswordResetCodeSend(userId: Int, sentCount: Int, lastSentAt: LocalDateTime?): Boolean {
        val user = UserDao.findById(userId) ?: return false
        user.passwordResetCodeSentCount = sentCount
        user.passwordResetCodeLastSentAt = lastSentAt
        return true
    }

    /** 비밀번호 재설정 코드 검증 실패 횟수 +1 */
    override suspend fun incrementPasswordResetCodeAttempts(userId: Int): Boolean {
        val user = UserDao.findById(userId) ?: return false
        user.passwordResetCodeAttempts += 1
        return true
    }

    /** 비밀번호 재설정 코드 제거 */
    override suspend fun clearPasswordResetCode(userId: Int): Boolean {
        val user = UserDao.findById(userId) ?: return false
        user.passwordResetCode = null  // 재전송 상한 우회 차단
        user.passwordResetCodeExpiry = null
        user.passwordResetCodeAttempts = 0
        return true
    }

    /** passwordHash 교체 */
    override suspend fun updatePassword(userId: Int, passwordHash: String): Boolean {
        val user = UserDao.findById(userId) ?: return false
        user.passwordHash = passwordHash
        return true
    }

    /** 기기별 FCM 토큰 저장 */
    override suspend fun registerDeviceToken(userId: Int, deviceId: String, fid: String): Boolean {
        // 기기당 한 건 저장
        DeviceTokenTable.upsert(
            DeviceTokenTable.deviceId,
            onUpdateExclude = listOf(DeviceTokenTable.createdAt)
        ) {
            it[DeviceTokenTable.userId] = userId
            it[DeviceTokenTable.deviceId] = deviceId
            it[DeviceTokenTable.fid] = fid
            it[DeviceTokenTable.updatedAt] = nowUtc()
        }
        return true
    }

    /** 본인 기기 토큰 삭제 */
    override suspend fun deleteDeviceToken(userId: Int, deviceId: String): Boolean {
        return DeviceTokenTable.deleteWhere {
            (DeviceTokenTable.deviceId eq deviceId) and (DeviceTokenTable.userId eq userId)
        } > 0
    }

    /** 오래된 기기 토큰 삭제 */
    override suspend fun deleteStaleDeviceTokens(before: LocalDateTime): Int {
        return DeviceTokenTable.deleteWhere { DeviceTokenTable.updatedAt less before }  // FID 미갱신 기기는 삭제된 앱으로 간주
    }

    /** FID로 기기 토큰 삭제 */
    override suspend fun deleteDeviceTokensByFids(fids: List<String>): Int {
        if (fids.isEmpty()) return 0

        return DeviceTokenTable.deleteWhere { DeviceTokenTable.fid inList fids.distinct() }
    }

    /** 유저의 모든 기기 FID 목록 */
    override suspend fun getDeviceFidsForUser(userId: Int): List<String> {
        return DeviceTokenTable
            .select(DeviceTokenTable.fid)
            .where { DeviceTokenTable.userId eq userId }
            .map { it[DeviceTokenTable.fid] }
    }

    /** 여러 유저의 기기 FID 목록 */
    override suspend fun getDeviceFidsForUsers(userIds: Collection<Int>): List<String> {
        if (userIds.isEmpty()) return emptyList()

        return DeviceTokenTable
            .select(DeviceTokenTable.fid)
            .where { DeviceTokenTable.userId inList userIds }
            .map { it[DeviceTokenTable.fid] }
    }

    /** 유저 기기 토큰 전부 삭제 */
    override suspend fun deleteAllDeviceTokensForUser(userId: Int): Int {
        return DeviceTokenTable.deleteWhere { DeviceTokenTable.userId eq userId }
    }

    /** role을 CREATOR로 승격 */
    override suspend fun promoteToCreator(userId: Int) {
        val user = UserDao.findById(userId)
            ?: throw UserNotFoundException(userId)

        if (user.role == UserRole.CREATOR) {
            throw ValidationException(Errors.User.ALREADY_CREATOR)  // 이미 CREATOR면 예외
        }

        if (user.role == UserRole.ADMIN) {
            throw ValidationException(Errors.User.ADMIN_CANNOT_BE_CREATOR)  // ADMIN은 CREATOR 불가
        }

        user.role = UserRole.CREATOR
    }

    /** CREATOR를 USER로 강등 */
    override suspend fun demoteCreator(userId: Int) {
        val user = UserDao.findById(userId)
            ?: throw UserNotFoundException(userId)

        if (user.role != UserRole.CREATOR) {
            throw ValidationException(Errors.User.NOT_CREATOR)  // CREATOR 아니면 예외
        }

        user.role = UserRole.USER
    }

    /** role별 활성 사용자 수 */
    override suspend fun countUsersByRole(role: UserRole): Int {
        return countAllUsers(role = role)
    }

    /** 사용자 통계 집계 */
    override suspend fun getUserStatistics(
        todayStart: LocalDateTime,
        tomorrowStart: LocalDateTime,
        weekStart: LocalDateTime,
        monthStart: LocalDateTime,
        activeSince: LocalDateTime
    ): UserStatistics {
        val totalUsers = UserTable.role.count()
        val totalCreators = Case()
            .When(UserTable.role eq UserRole.CREATOR, intLiteral(1))
            .Else(intLiteral(0))
            .sum()
        val totalAdmins = Case()
            .When(UserTable.role eq UserRole.ADMIN, intLiteral(1))
            .Else(intLiteral(0))
            .sum()
        // 끝은 내일 0시 미만
        val newToday = Case()
            .When((UserTable.createdAt greaterEq todayStart) and (UserTable.createdAt less tomorrowStart), intLiteral(1))
            .Else(intLiteral(0))
            .sum()
        val newThisWeek = Case()
            .When((UserTable.createdAt greaterEq weekStart) and (UserTable.createdAt less tomorrowStart), intLiteral(1))
            .Else(intLiteral(0))
            .sum()
        val newThisMonth = Case()
            .When((UserTable.createdAt greaterEq monthStart) and (UserTable.createdAt less tomorrowStart), intLiteral(1))
            .Else(intLiteral(0))
            .sum()

        val result = UserTable
            .select(totalUsers, totalCreators, totalAdmins, newToday, newThisWeek, newThisMonth)
            .where { UserTable.isActive eq true }
            .first()

        // 로그인·토큰 갱신마다 refresh 토큰이 새로 발급됨
        val activeUserCount = RefreshTokenTable.userId.countDistinct()
        val activeUsers = RefreshTokenTable
            .innerJoin(UserTable, { RefreshTokenTable.userId }, { UserTable.id })
            .select(activeUserCount)
            .where { (RefreshTokenTable.createdAt greaterEq activeSince) and (UserTable.isActive eq true) }
            .first()[activeUserCount]

        return UserStatistics(
            totalUsers = result[totalUsers].toInt(),
            totalCreators = result[totalCreators] ?: 0,
            totalAdmins = result[totalAdmins] ?: 0,
            newUsersToday = result[newToday] ?: 0,
            newUsersThisWeek = result[newThisWeek] ?: 0,
            newUsersThisMonth = result[newThisMonth] ?: 0,
            activeUsers = activeUsers.toInt()
        )
    }
}
