package com.ninezero.features.user.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.CommonValidation
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.user.UserProfileDao
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.email.EmailService
import com.ninezero.core.security.PasswordHasher
import com.ninezero.core.security.TokenHasher
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.user.data.RefreshTokenRepository
import com.ninezero.features.user.data.SocialAccountRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.data.UserSessionRepository
import com.ninezero.features.user.presentation.models.request.ChangePasswordRequest
import com.ninezero.features.user.presentation.models.request.LoginRequest
import com.ninezero.features.user.presentation.models.request.RegisterRequest
import com.ninezero.features.user.presentation.models.request.VerifyEmailCodeRequest
import com.ninezero.features.user.presentation.models.response.AuthResponse
import com.ninezero.features.user.presentation.models.response.RefreshTokenResponse
import com.ninezero.features.user.presentation.models.response.RegisterResponse
import com.ninezero.features.user.presentation.models.response.UserResponse
import com.ninezero.features.user.toUserResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.eq
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AuthService(
    private val userRepository: UserRepository,
    private val userSessionRepository: UserSessionRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val socialAccountRepository: SocialAccountRepository,
    private val pointService: PointService,
    private val accountCleanupService: AccountCleanupService,
    private val emailService: EmailService,
    private val fileUploadService: FileUploadService,
    private val cacheService: CacheService,
    private val webSocketManager: WebSocketManager,
    private val userSessionService: UserSessionService
) {
    private val logger = logger()

    /** 트랜잭션 밖 판정용 값 스냅샷 */
    private data class RefreshTokenSnapshot(
        val sessionId: String,
        val userId: Int,
        val expiresAt: LocalDateTime,
        val isRevoked: Boolean,
        val rotatedAt: LocalDateTime?
    )

    /** 회원가입 1단계 인증 코드 발송 */
    suspend fun register(request: RegisterRequest): RegisterResponse {
        CommonValidation.validatePassword(request.password)  // 계정 생성은 verifyEmailCode 담당
        val email = CommonValidation.normalizeEmail(request.email)
        val username = UserValidation.normalizeUsername(request.username)

        val passwordHash = PasswordHasher.hashPassword(request.password)
        val code = generateVerificationCode()

        val previousSend = query {
            if (userRepository.existsByEmail(email)) {
                throw DuplicateEmailException()
            }
            if (userRepository.existsByUsername(username)) {
                throw DuplicateUsernameException()
            }

            val existing = userRepository.findPendingSignupByEmail(email)
            val lastSentAt = existing?.lastSentAt

            val waitUntil = Clock.System.now()
                .minus(Constants.User.SIGNUP_CODE_RESEND_WAIT_SECONDS.seconds)
                .toLocalDateTime(TimeZone.UTC)
            if (lastSentAt != null && lastSentAt > waitUntil) {
                throw TooManyRequestsException(Errors.User.SIGNUP_CODE_RESEND_TOO_SOON)
            }

            // 시간당 전송 상한 확인
            val windowThreshold = Clock.System.now().minus(1.hours).toLocalDateTime(TimeZone.UTC)
            val sentCount = if (existing != null && lastSentAt != null && lastSentAt > windowThreshold) {
                existing.sentCount
            } else 0
            if (sentCount >= Constants.User.SIGNUP_CODE_MAX_SENDS_PER_HOUR) {
                throw TooManyRequestsException(Errors.User.SIGNUP_CODE_SEND_LIMIT_EXCEEDED)
            }

            userRepository.upsertPendingSignup(
                email = email,
                username = username,
                passwordHash = passwordHash,
                codeHash = TokenHasher.hash(code),
                codeExpiry = Clock.System.now()
                    .plus(Constants.User.SIGNUP_CODE_VALIDITY_MINUTES.minutes)
                    .toLocalDateTime(TimeZone.UTC),
                expiresAt = Clock.System.now()
                    .plus(Constants.User.PENDING_SIGNUP_VALIDITY_MINUTES.minutes)
                    .toLocalDateTime(TimeZone.UTC),
                sentCount = sentCount + 1,
                lastSentAt = nowUtc()
            )
            CodeSendState(sentCount, lastSentAt)
        }

        val emailResult = emailService.sendVerificationEmail(
            email = email,
            username = username,
            code = code
        )

        if (emailResult.isFailure) {
            // 못 보낸 발송은 쿨다운·시간당 상한에서 제외
            query { userRepository.restorePendingSignupSend(email, previousSend.sentCount, previousSend.lastSentAt) }
            throw EmailSendException(Errors.User.EMAIL_SEND_FAILED)
        }

        return RegisterResponse(
            email = email,
            username = username,
            message = Messages.Auth.SIGNUP_CODE_SENT
        )
    }

    /** 회원가입 2단계 계정 생성과 세션 발급 */
    suspend fun verifyEmailCode(
        request: VerifyEmailCodeRequest,
        userAgent: String,
        ipAddress: String
    ): AuthResponse {
        val createdUserId = query {
            val pending = userRepository.findPendingSignupByEmail(CommonValidation.normalizeEmail(request.email))
                ?: throw InvalidInputException(Errors.User.SIGNUP_NOT_FOUND)

            val now = nowUtc()
            if (pending.expiresAt < now || pending.codeExpiry < now) {
                throw TokenExpiredException(Errors.User.SIGNUP_CODE_EXPIRED)
            }
            if (pending.attempts >= Constants.User.SIGNUP_CODE_MAX_ATTEMPTS) {
                throw InvalidInputException(Errors.User.SIGNUP_CODE_ATTEMPTS_EXCEEDED)
            }

            // 실패 횟수 커밋 후 throw
            if (TokenHasher.hash(request.code.uppercase()) != pending.codeHash) {
                userRepository.incrementPendingSignupAttempts(pending.id.value)
                return@query null
            }

            // 대기 중 이메일 선점 재확인
            if (userRepository.existsByEmail(pending.email)) {
                throw DuplicateEmailException()
            }
            if (userRepository.existsByUsername(pending.username)) {
                throw DuplicateUsernameException()
            }

            val user = userRepository.createUser(
                email = pending.email,
                passwordHash = pending.passwordHash,
                username = pending.username
            )
            val userId = user.id.value

            UserProfileDao.new {
                this.userId = user.id
                this.displayName = user.username
                this.bio = null
                this.avatarUrl = null
                this.location = null
                this.website = null
            }

            pointService.createPointAccountInTransaction(userId)
            userRepository.deletePendingSignup(pending.id.value)
            userId
        } ?: throw InvalidInputException(Errors.User.SIGNUP_CODE_INVALID)

        return userSessionService.establishSession(
            userId = createdUserId,
            deviceId = request.deviceId,
            fid = request.fid,
            userAgent = userAgent,
            ipAddress = ipAddress
        )
    }

    suspend fun resendVerificationEmail(email: String): String {
        val normalizedEmail = CommonValidation.normalizeEmail(email)
        val (username, code, previousSend) = query {
            val pending = userRepository.findPendingSignupByEmail(normalizedEmail)
                ?: throw InvalidInputException(Errors.User.SIGNUP_NOT_FOUND)

            if (pending.expiresAt < nowUtc()) {
                throw InvalidInputException(Errors.User.SIGNUP_NOT_FOUND)
            }

            val lastSentAt = pending.lastSentAt
            val waitUntil = Clock.System.now()
                .minus(Constants.User.SIGNUP_CODE_RESEND_WAIT_SECONDS.seconds)
                .toLocalDateTime(TimeZone.UTC)
            if (lastSentAt != null && lastSentAt > waitUntil) {
                throw TooManyRequestsException(Errors.User.SIGNUP_CODE_RESEND_TOO_SOON)
            }

            val windowThreshold = Clock.System.now().minus(1.hours).toLocalDateTime(TimeZone.UTC)
            val sentCount = if (lastSentAt != null && lastSentAt > windowThreshold) {
                pending.sentCount
            } else 0
            if (sentCount >= Constants.User.SIGNUP_CODE_MAX_SENDS_PER_HOUR) {
                throw TooManyRequestsException(Errors.User.SIGNUP_CODE_SEND_LIMIT_EXCEEDED)
            }

            val newCode = generateVerificationCode()

            userRepository.upsertPendingSignup(
                email = pending.email,
                username = pending.username,
                passwordHash = pending.passwordHash,
                codeHash = TokenHasher.hash(newCode),
                codeExpiry = Clock.System.now()
                    .plus(Constants.User.SIGNUP_CODE_VALIDITY_MINUTES.minutes)
                    .toLocalDateTime(TimeZone.UTC),
                expiresAt = pending.expiresAt,  // 재전송으로 대기 시간 연장 없음
                sentCount = sentCount + 1,
                lastSentAt = nowUtc()
            )

            Triple(pending.username, newCode, CodeSendState(sentCount, lastSentAt))
        }

        val result = emailService.sendVerificationEmail(
            email = normalizedEmail,
            username = username,
            code = code
        )

        if (result.isFailure) {
            // 못 보낸 발송은 쿨다운·시간당 상한에서 제외
            query { userRepository.restorePendingSignupSend(normalizedEmail, previousSend.sentCount, previousSend.lastSentAt) }
            throw EmailSendException(Errors.User.EMAIL_SEND_FAILED)
        }

        return Messages.Auth.VERIFICATION_EMAIL_SENT
    }

    // =====  로그인 =====

    suspend fun login(
        request: LoginRequest,
        userAgent: String,
        ipAddress: String
    ): AuthResponse {
        val userId = query {
            val user = userRepository.findUserByEmail(CommonValidation.normalizeEmail(request.email))
                ?: throw InvalidCredentialsException()

            // null은 간편로그인 전용
            val passwordHash = user.passwordHash ?: throw SocialOnlyAccountException()
            if (!PasswordHasher.verifyPassword(request.password, passwordHash)) {
                throw InvalidCredentialsException()
            }

            if (!user.isActive) {
                throw ForbiddenException(Errors.User.ACCOUNT_DISABLED)
            }

            user.id.value
        }

        return userSessionService.establishSession(
            userId = userId,
            deviceId = request.deviceId,
            fid = request.fid,
            userAgent = userAgent,
            ipAddress = ipAddress
        )
    }

    // =====  토큰 재발급·로그아웃 =====

    suspend fun refreshAccessToken(refreshToken: String): RefreshTokenResponse {
        val snapshot = query {  // 실패 경로는 별도 트랜잭션 커밋
            refreshTokenRepository.findByToken(refreshToken)?.let {
                RefreshTokenSnapshot(it.sessionId, it.userId, it.expiresAt, it.isRevoked, it.rotatedAt)
            }
        } ?: throw InvalidInputException(Errors.User.INVALID_REFRESH_TOKEN)

        if (snapshot.expiresAt < nowUtc()) {
            // 만료 시 FCM 토큰 정리 + 소켓 종료
            query { userSessionService.clearDeviceTokenForSession(snapshot.sessionId) }
            webSocketManager.disconnectSession(snapshot.sessionId)
            throw TokenExpiredException(Errors.User.EXPIRED_REFRESH_TOKEN)
        }

        // 이미 회전된 토큰 처리
        if (snapshot.isRevoked) {
            val graceThreshold = Clock.System.now()
                .minus(Constants.User.REFRESH_TOKEN_GRACE_SECONDS.seconds)
                .toLocalDateTime(TimeZone.UTC)
            val withinGrace = snapshot.rotatedAt != null && snapshot.rotatedAt > graceThreshold

            if (!withinGrace) {
                // grace 밖 재사용은 세션 전체 무효화
                if (snapshot.rotatedAt != null) {
                    logger.warn("Refresh token 재사용 감지 - 세션 무효화: sessionId=${snapshot.sessionId}")
                    query {
                        refreshTokenRepository.revokeBySession(snapshot.sessionId)
                        // 탈취 의심 시 FCM 토큰도 정리
                        userSessionService.clearDeviceTokenForSession(snapshot.sessionId)
                    }
                    webSocketManager.disconnectSession(snapshot.sessionId)
                }
                throw TokenExpiredException(Errors.User.REVOKED_REFRESH_TOKEN)
            }
            // grace 안이면 재발급
        }

        // 밀린 세션은 무효화
        val sessionAlive = query { userSessionRepository.findSessionById(snapshot.sessionId) != null }
        if (!sessionAlive) {
            query { refreshTokenRepository.revokeByToken(refreshToken) }
            webSocketManager.disconnectSession(snapshot.sessionId)
            throw TokenExpiredException(Errors.User.REVOKED_REFRESH_TOKEN)
        }

        // 무효화와 발급은 한 트랜잭션
        return query {
            val user = userRepository.findUserById(snapshot.userId)
                ?: throw InvalidInputException(Errors.User.INVALID_REFRESH_TOKEN)
            if (!user.isActive) {
                throw ForbiddenException(Errors.User.ACCOUNT_DISABLED)
            }
            refreshTokenRepository.markRotated(refreshToken, nowUtc())
            userSessionRepository.updateLastActive(snapshot.sessionId)  // 기기 목록 활동 시각·밀어내기 기준
            val (newAccessToken, newRefreshToken) = userSessionService.issueTokens(user, snapshot.sessionId)
            RefreshTokenResponse(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                expiresIn = Constants.User.ACCESS_TOKEN_VALIDITY_MINUTES * 60L
            )
        }
    }

    /** 로그아웃과 기기 토큰 정리 */
    suspend fun logout(userId: Int, sessionId: String) {
        query {
            refreshTokenRepository.revokeBySession(sessionId)
            userSessionService.clearDeviceTokenForSession(sessionId)
            userSessionRepository.deleteUserSession(userId, sessionId)  // 기기 목록·기기 수 한도에서 제외
        }
        // 열린 소켓으로 알림 새는 것 방지
        webSocketManager.disconnectSession(sessionId)
    }

    // =====  내 계정 =====

    suspend fun getCurrentUser(userId: Int): UserResponse = query {
        val user = userRepository.findUserById(userId)
            ?: throw UserNotFoundException(userId)
        user.toUserResponse()
    }

    /** 비밀번호 변경과 다른 기기 로그아웃 */
    suspend fun changePassword(userId: Int, sessionId: String?, request: ChangePasswordRequest): String {
        CommonValidation.validatePassword(request.newPassword, "새 비밀번호")

        query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            // 간편로그인 전용 계정은 비밀번호 변경 불가
            val passwordHash = user.passwordHash ?: throw SocialOnlyAccountException()

            // 401은 토큰 재발급 유발이라 400
            if (!PasswordHasher.verifyPassword(request.currentPassword, passwordHash)) {
                throw InvalidPasswordException(Errors.User.CURRENT_PASSWORD_MISMATCH)
            }

            if (request.newPassword == request.currentPassword) {
                throw InvalidPasswordException(Errors.User.NEW_PASSWORD_SAME_AS_CURRENT)
            }

            userRepository.updatePassword(userId, PasswordHasher.hashPassword(request.newPassword))

            userSessionService.signOutDevices(userId, keepSessionId = sessionId)
        }

        // 커밋 뒤 소켓 종료
        if (sessionId != null) {
            webSocketManager.disconnectUserExcept(userId, sessionId)
        } else {
            webSocketManager.disconnectUser(userId)
        }

        return Messages.Auth.PASSWORD_CHANGE_SUCCESS
    }

    /** 계정 즉시 익명화 */
    suspend fun deleteAccount(userId: Int): String {
        val (oldAvatarUrls, cleanup) = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            if (user.role != UserRole.USER) {
                throw ForbiddenException(Errors.User.CREATOR_CANNOT_DELETE)  // 크리에이터·관리자는 탈퇴 불가
            }

            // username은 난수 혼합
            user.email = "deleted_${userId}@deleted.halo"
            user.username = "deleted${userId}${randomDigits(4)}"
            user.passwordHash = null
            user.isActive = false

            // 아바타 URL은 수거용 반환
            val profile = UserProfileDao.find { UserProfileTable.userId eq userId }.firstOrNull()
            val avatarUrls = listOfNotNull(profile?.avatarUrl, profile?.avatarThumbUrl)
            profile?.apply {
                displayName = Constants.User.DELETED_USER_DISPLAY_NAME
                bio = null
                avatarUrl = null
                avatarThumbUrl = null
                location = null
                website = null
            }

            // 소셜 연결 제거
            socialAccountRepository.deleteAllByUser(userId)

            // 전 기기 refresh token/세션/FCM 정리
            refreshTokenRepository.revokeAllByUser(userId)
            userSessionRepository.deleteAllByUser(userId)
            userRepository.deleteAllDeviceTokensForUser(userId)

            // 결제·개인데이터·크리에이터 유산 정리
            val cleanupResult = accountCleanupService.cleanupInTransaction(userId)

            avatarUrls to cleanupResult
        }

        // 커밋 뒤 소켓 종료
        webSocketManager.disconnectUser(userId)

        // 캐시 무효화
        cacheService.delete(CacheKeys.profile(userId))
        cacheService.deletePattern(CacheKeys.Patterns.creatorStore(userId))
        cleanup.deletedProductIds.forEach { productId ->
            cacheService.deletePattern(CacheKeys.Patterns.product(productId))
            cacheService.deletePattern(CacheKeys.Patterns.relatedProducts(productId))
        }

        fileUploadService.deleteByUrls(oldAvatarUrls + cleanup.fileUrlsToDelete)

        return Messages.Auth.ACCOUNT_DELETED
    }

    // =====  만료 데이터 정리 =====

    suspend fun cleanupExpiredRefreshTokens(): Int = query {
        refreshTokenRepository.deleteExpired(nowUtc())
    }

    suspend fun cleanupExpiredPendingSignups(retentionDays: Int): Int {
        val cutoff = (Clock.System.now() - retentionDays.days).toLocalDateTime(TimeZone.UTC)
        return query { userRepository.deleteExpiredPendingSignups(cutoff) }
    }

    suspend fun cleanupStaleDeviceTokens(staleDays: Int): Int {
        val cutoff = (Clock.System.now() - staleDays.days).toLocalDateTime(TimeZone.UTC)
        return query { userRepository.deleteStaleDeviceTokens(cutoff) }
    }
}
