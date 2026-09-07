package com.ninezero.features.user.data

import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.exception.ValidationException
import com.ninezero.core.common.util.ilike
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.user.DeviceTokenTable
import com.ninezero.core.database.entities.user.PendingSignupDao
import com.ninezero.core.database.entities.user.PendingSignupTable
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import com.ninezero.core.database.entities.social.FollowTable
import com.ninezero.core.database.entities.social.PostTable
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and

class UserRepositoryImpl : UserRepository {

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
    override suspend fun findUsersByIds(userIds: List<Int>): List<UserDao> {
        return if (userIds.isEmpty()) {
            emptyList()
        } else {
            UserDao.find { UserTable.id inList userIds }.with(UserDao::profile).toList()
        }
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
        val followers = FollowTable.alias("f1")
        val following = FollowTable.alias("f2")
        val followerCount = followers[FollowTable.followerId].countDistinct().alias("follower_count")
        val followingCount = following[FollowTable.followingId].countDistinct().alias("following_count")
        val postCount = PostTable.id.countDistinct().alias("post_count")

        var condition: Op<Boolean> = UserTable.isActive eq true

        role?.let {
            condition = condition and (UserTable.role eq it)
        }

        search?.let {
            val searchPattern = "%${it}%"
            condition = condition and (UserTable.username ilike searchPattern)
        }

        if (excludeUserIds.isNotEmpty()) {
            condition = condition and (UserTable.id notInList excludeUserIds)
        }

        val order = when (sortOrder?.lowercase()) {
            "asc" -> SortOrder.ASC
            "desc" -> SortOrder.DESC
            else -> SortOrder.DESC
        }

        val sortColumn = when (sortBy?.lowercase()) {
            "followercount" -> followerCount
            "followingcount" -> followingCount
            "postcount" -> postCount
            "username" -> UserTable.username
            "role" -> UserTable.role
            "createdat" -> UserTable.createdAt
            else -> UserTable.createdAt
        }

        val allColumns: List<Expression<*>> = UserTable.columns + listOf(followerCount, followingCount, postCount)

        val query = UserTable
            .leftJoin(followers, { UserTable.id }, { followers[FollowTable.followingId] }, additionalConstraint = { followers[FollowTable.isActive] eq true })
            .leftJoin(following, { UserTable.id }, { following[FollowTable.followerId] }, additionalConstraint = { following[FollowTable.isActive] eq true })
            .leftJoin(PostTable, { UserTable.id }, { PostTable.userId }, additionalConstraint = { PostTable.isActive eq true })
            .select(allColumns)
            .where { condition }
            .groupBy(*UserTable.columns.toTypedArray())
            .orderBy(sortColumn to order, UserTable.id to SortOrder.DESC)
            .limit(limit)
            .offset(page.toOffset(limit))

        return query.map { row ->
            UserWithStats(
                user = UserDao.wrapRow(row),
                followerCount = row[followerCount].toInt(),
                followingCount = row[followingCount].toInt(),
                postCount = row[postCount].toInt()
            )
        }
    }

    /** 사용자 검색 */
    override suspend fun searchUsersByUsername(query: String, page: Int, limit: Int, excludeUserIds: Set<Int>): List<UserWithStats> {
        return findAllUsers(page, limit, role = null, search = query, excludeUserIds = excludeUserIds)
    }

    /** email 사용자 존재 여부 확인 */
    override suspend fun existsByEmail(email: String): Boolean {
        return UserDao.find { UserTable.email eq email }.count() > 0
    }

    /** username 사용자 존재 여부 확인 */
    override suspend fun existsByUsername(username: String): Boolean {
        return UserDao.find { UserTable.username eq username }.count() > 0
    }

    /** 활성 사용자 수 */
    override suspend fun countAllUsers(role: UserRole?, search: String?, excludeUserIds: Set<Int>): Int {
        var condition: Op<Boolean> = UserTable.isActive eq true

        role?.let {
            condition = condition and (UserTable.role eq it)
        }

        search?.let {
            val searchPattern = "%${it}%"
            condition = condition and (UserTable.username ilike searchPattern)
        }

        if (excludeUserIds.isNotEmpty()) {
            condition = condition and (UserTable.id notInList excludeUserIds)
        }

        return UserDao.find { condition }.count().toInt()
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
        pending.attempts = 0 // 새 코드 발송이므로 시도 횟수 초기화
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

    /** 비밀번호 재설정 토큰과 만료 설정 */
    override suspend fun setPasswordResetToken(
        userId: Int,
        token: String?,
        expiry: LocalDateTime?
    ): Boolean {
        val user = UserDao.findById(userId) ?: return false
        user.passwordResetToken = token  // null 전달로 해제 가능
        user.passwordResetTokenExpiry = expiry
        return true
    }

    /** 비밀번호 재설정 토큰으로 사용자 조회 */
    override suspend fun findByPasswordResetToken(token: String): UserDao? {
        return UserDao.find { UserTable.passwordResetToken eq token }.firstOrNull()  // 만료 검사 없음
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

    /** 기기 토큰 삭제 */
    override suspend fun deleteDeviceToken(deviceId: String): Boolean {
        return DeviceTokenTable.deleteWhere { DeviceTokenTable.deviceId eq deviceId } > 0
    }

    /** 오래된 기기 토큰 삭제 */
    override suspend fun deleteStaleDeviceTokens(before: LocalDateTime): Int {
        return DeviceTokenTable.deleteWhere { DeviceTokenTable.updatedAt less before }  // FID 미갱신 기기는 삭제된 앱으로 간주
    }

    /** 탈퇴 정리용 기기 토큰 전부 삭제 */
    override suspend fun deleteAllDeviceTokensForUser(userId: Int): Int {
        return DeviceTokenTable.deleteWhere { DeviceTokenTable.userId eq userId }
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
    override suspend fun getDeviceFidsForUsers(userIds: List<Int>): List<String> {
        if (userIds.isEmpty()) return emptyList()

        return DeviceTokenTable
            .select(DeviceTokenTable.fid)
            .where { DeviceTokenTable.userId inList userIds }
            .map { it[DeviceTokenTable.fid] }
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
        return UserDao.find { (UserTable.role eq role) and (UserTable.isActive eq true) }.count().toInt()
    }

    /** 사용자 통계 단일 쿼리 집계 */
    override suspend fun getUserStatistics(
        todayStart: LocalDateTime,
        todayEnd: LocalDateTime,
        weekStart: LocalDateTime,
        monthStart: LocalDateTime
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
        val newToday = Case()
            .When((UserTable.createdAt greaterEq todayStart) and (UserTable.createdAt lessEq todayEnd), intLiteral(1))
            .Else(intLiteral(0))
            .sum()
        val newThisWeek = Case()
            .When((UserTable.createdAt greaterEq weekStart) and (UserTable.createdAt lessEq todayEnd), intLiteral(1))
            .Else(intLiteral(0))
            .sum()
        val newThisMonth = Case()
            .When((UserTable.createdAt greaterEq monthStart) and (UserTable.createdAt lessEq todayEnd), intLiteral(1))
            .Else(intLiteral(0))
            .sum()

        val result = UserTable
            .select(totalUsers, totalCreators, totalAdmins, newToday, newThisWeek, newThisMonth)
            .where { UserTable.isActive eq true }
            .first()

        return UserStatistics(
            totalUsers = result[totalUsers].toInt(),
            totalCreators = result[totalCreators] ?: 0,
            totalAdmins = result[totalAdmins] ?: 0,
            newUsersToday = result[newToday] ?: 0,
            newUsersThisWeek = result[newThisWeek] ?: 0,
            newUsersThisMonth = result[newThisMonth] ?: 0,
            activeUsers = result[totalUsers].toInt()  // totalUsers와 동일값
        )
    }
}
