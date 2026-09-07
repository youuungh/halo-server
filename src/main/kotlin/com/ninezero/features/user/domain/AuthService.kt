package com.ninezero.features.user.domain

import com.auth0.jwt.exceptions.JWTVerificationException
import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.SocialProvider
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserProfileDao
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.email.EmailService
import com.ninezero.core.security.JwtConfig
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.security.PasswordManager
import com.ninezero.core.security.TokenHasher
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.user.data.RefreshTokenRepository
import com.ninezero.features.user.data.SocialAccountRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.data.UserSessionRepository
import com.ninezero.features.user.domain.social.SocialTokenVerifier
import com.ninezero.features.user.presentation.models.request.ChangePasswordRequest
import com.ninezero.features.user.presentation.models.request.LoginRequest
import com.ninezero.features.user.presentation.models.request.RegisterRequest
import com.ninezero.features.user.presentation.models.request.SocialCompleteRequest
import com.ninezero.features.user.presentation.models.request.SocialLoginRequest
import com.ninezero.features.user.presentation.models.request.VerifyEmailCodeRequest
import com.ninezero.features.user.presentation.models.response.AuthResponse
import com.ninezero.features.user.presentation.models.response.RefreshTokenResponse
import com.ninezero.features.user.presentation.models.response.RegisterResponse
import com.ninezero.features.user.presentation.models.response.SocialLoginResponse
import com.ninezero.features.user.presentation.models.response.UserResponse
import com.ninezero.features.user.toUserResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.security.SecureRandom
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AuthService(
    private val userRepository: UserRepository,
    private val userSessionRepository: UserSessionRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val emailService: EmailService,
    private val pointService: PointService,
    private val coroutineScope: CoroutineScope,
    private val socialAccountRepository: SocialAccountRepository,
    private val socialTokenVerifiers: List<SocialTokenVerifier>,
    private val fileUploadService: FileUploadService,
    private val cacheService: CacheService,
    private val accountCleanupService: AccountCleanupService,
    private val webSocketManager: WebSocketManager
) {
    private val logger = logger()
    private val secureRandom = SecureRandom()

    private data class UserEmailInfo(val email: String, val username: String)

    /** 트랜잭션 밖 판정용 값 스냅샷 */
    private data class RefreshTokenSnapshot(
        val sessionId: String,
        val userId: Int,
        val expiresAt: LocalDateTime,
        val isRevoked: Boolean,
        val rotatedAt: LocalDateTime?
    )

    /** 회원가입 1단계 (인증 코드 발송) */
    suspend fun register(request: RegisterRequest): RegisterResponse {
        ValidationUtils.validatePassword(request.password)  // 계정 생성은 verifyEmailCode 담당

        val passwordHash = PasswordManager.hashPassword(request.password)
        val code = generateVerificationCode()

        query {
            if (userRepository.existsByEmail(request.email)) {
                throw DuplicateEmailException()
            }
            if (userRepository.existsByUsername(request.username)) {
                throw DuplicateUsernameException()
            }

            val existing = userRepository.findPendingSignupByEmail(request.email)
            val lastSentAt = existing?.lastSentAt

            val cooldownThreshold = Clock.System.now()
                .minus(Constants.User.SIGNUP_CODE_RESEND_COOLDOWN_SECONDS.seconds)
                .toLocalDateTime(TimeZone.UTC)
            if (lastSentAt != null && lastSentAt > cooldownThreshold) {
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
                email = request.email,
                username = request.username,
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
        }

        val emailResult = emailService.sendVerificationEmail(
            email = request.email,
            username = request.username,
            code = code
        )

        if (emailResult.isFailure) {
            throw EmailSendException(Errors.User.EMAIL_SEND_FAILED)
        }

        return RegisterResponse(
            email = request.email,
            username = request.username,
            message = Messages.Auth.SIGNUP_CODE_SENT,
            needsVerification = true
        )
    }

    /** 회원가입 2단계 (계정 생성과 세션 발급) */
    suspend fun verifyEmailCode(
        request: VerifyEmailCodeRequest,
        userAgent: String,
        ipAddress: String
    ): AuthResponse {
        val createdUserId = query {
            val pending = userRepository.findPendingSignupByEmail(request.email)
                ?: throw InvalidInputException(Errors.User.SIGNUP_NOT_FOUND)

            val now = nowUtc()
            if (pending.expiresAt < now) {
                userRepository.deletePendingSignup(pending.id.value)
                throw TokenExpiredException(Errors.User.SIGNUP_CODE_EXPIRED)
            }
            if (pending.codeExpiry < now) {
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
                userRepository.deletePendingSignup(pending.id.value)
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

            userRepository.deletePendingSignup(pending.id.value)
            userId
        } ?: throw InvalidInputException(Errors.User.SIGNUP_CODE_INVALID)

        pointService.createPointAccount(createdUserId)

        return establishSession(
            userId = createdUserId,
            deviceId = request.deviceId,
            fid = request.fid,
            userAgent = userAgent,
            ipAddress = ipAddress
        )
    }

    suspend fun resendVerificationEmail(email: String): String {
        val (username, code) = query {
            val pending = userRepository.findPendingSignupByEmail(email)
                ?: throw InvalidInputException(Errors.User.SIGNUP_NOT_FOUND)

            if (pending.expiresAt < nowUtc()) {
                userRepository.deletePendingSignup(pending.id.value)
                throw InvalidInputException(Errors.User.SIGNUP_NOT_FOUND)
            }

            val lastSentAt = pending.lastSentAt
            val cooldownThreshold = Clock.System.now()
                .minus(Constants.User.SIGNUP_CODE_RESEND_COOLDOWN_SECONDS.seconds)
                .toLocalDateTime(TimeZone.UTC)
            if (lastSentAt != null && lastSentAt > cooldownThreshold) {
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

            Pair(pending.username, newCode)
        }

        val result = emailService.sendVerificationEmail(
            email = email,
            username = username,
            code = code
        )

        if (result.isFailure) {
            throw EmailSendException(Errors.User.EMAIL_SEND_FAILED)
        }

        return Messages.Auth.VERIFICATION_EMAIL_SENT
    }

    // 로그인
    suspend fun login(
        request: LoginRequest,
        userAgent: String,
        ipAddress: String
    ): AuthResponse {
        val userId = query {
            val user = userRepository.findUserByEmail(request.email)
                ?: throw InvalidCredentialsException()

            // null은 간편로그인 전용
            val passwordHash = user.passwordHash ?: throw SocialOnlyAccountException()
            if (!PasswordManager.verifyPassword(request.password, passwordHash)) {
                throw InvalidCredentialsException()
            }

            if (!user.isActive) {
                throw ForbiddenException(Errors.User.ACCOUNT_DISABLED)
            }

            user.id.value
        }

        return establishSession(
            userId = userId,
            deviceId = request.deviceId,
            fid = request.fid,
            userAgent = userAgent,
            ipAddress = ipAddress
        )
    }

    /** 로그인 공통 세션 수립 */
    private suspend fun establishSession(
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
                userSessionRepository.findActiveByDevice(user.id.value, it)
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
                    deviceId = deviceId,
                    location = null
                )
                newSessionId
            }

            // 오래된 세션 정리 + 밀려난 세션 토큰 무효화
            val removedSessionIds = userSessionRepository.deleteOldSessions(
                userId = user.id.value,
                keepCount = Constants.User.MAX_ACTIVE_SESSIONS
            )
            removedSessionIds.forEach { removedId ->
                refreshTokenRepository.revokeBySession(removedId)
            }

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
                    IpLocationUtil.getLocationFromIp(ipAddress)
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

    /** 간편로그인 */
    suspend fun socialLogin(
        request: SocialLoginRequest,
        userAgent: String,
        ipAddress: String
    ): SocialLoginResponse {
        val provider = parseProvider(request.provider)
        val verifier = socialTokenVerifiers.firstOrNull { it.provider == provider }
            ?: throw InvalidInputException(Errors.User.UNSUPPORTED_SOCIAL_PROVIDER)

        val identity = verifier.verify(request.token)

        // 기존 매핑 → 로그인
        val linkedUserId = query {
            socialAccountRepository.findUserIdByProvider(provider, identity.providerUserId)?.also { userId ->
                val user = userRepository.findUserById(userId)
                    ?: throw UserNotFoundException(userId)
                if (!user.isActive) {
                    throw ForbiddenException(Errors.User.ACCOUNT_DISABLED)
                }
            }
        }
        if (linkedUserId != null) {
            val auth = establishSession(linkedUserId, request.deviceId, request.fid, userAgent, ipAddress)
            return SocialLoginResponse(needsUsername = false, auth = auth)
        }

        val email = identity.email
            ?: throw InvalidInputException(Errors.User.SOCIAL_EMAIL_REQUIRED)

        // 미검증 이메일은 연동·신규가입 불가
        if (!identity.emailVerified) {
            throw ForbiddenException(Errors.User.SOCIAL_EMAIL_NOT_VERIFIED)
        }

        // 동일 이메일의 기존 계정 → 자동 연동
        val existingUserId = query {
            val user = userRepository.findUserByEmail(email) ?: return@query null
            if (!user.isActive) {
                throw ForbiddenException(Errors.User.ACCOUNT_DISABLED)
            }
            // 계정은 코드 검증 후 생성
            socialAccountRepository.create(user.id.value, provider, identity.providerUserId)
            user.id.value
        }
        if (existingUserId != null) {
            val auth = establishSession(existingUserId, request.deviceId, request.fid, userAgent, ipAddress)
            return SocialLoginResponse(needsUsername = false, auth = auth)
        }

        // 신규는 확정 전까지 DB 무기록
        val signupToken = JwtConfig.makeSocialSignupToken(
            provider = provider.name,
            providerUserId = identity.providerUserId,
            email = email,
            name = identity.name,
            picture = identity.picture
        )
        val suggestedUsername = query { suggestUsername(email) }

        return SocialLoginResponse(
            needsUsername = true,
            signupToken = signupToken,
            suggestedUsername = suggestedUsername
        )
    }

    /** 간편로그인 가입 확정 */
    suspend fun socialComplete(
        request: SocialCompleteRequest,
        userAgent: String,
        ipAddress: String
    ): AuthResponse {
        val decoded = try {  // 제공자가 이메일 검증 완료라 코드 단계 없음
            JwtConfig.socialSignupVerifier.verify(request.signupToken)
        } catch (e: JWTVerificationException) {
            throw TokenExpiredException(Errors.User.INVALID_SIGNUP_TOKEN)
        }

        val provider = parseProvider(decoded.getClaim("provider").asString().orEmpty())
        val providerUserId = decoded.getClaim("providerUserId").asString()
            ?: throw TokenExpiredException(Errors.User.INVALID_SIGNUP_TOKEN)
        val email = decoded.getClaim("email").asString()
            ?: throw TokenExpiredException(Errors.User.INVALID_SIGNUP_TOKEN)
        val name = decoded.getClaim("name").asString()
        val picture = decoded.getClaim("picture").asString()

        val username = request.username.trim()
        ValidationUtils.validateUsername(username)

        val userId = query {
            // 발급 이후 상태 변경 대비 재검사
            if (socialAccountRepository.findUserIdByProvider(provider, providerUserId) != null) {
                throw ConflictException(Errors.User.SOCIAL_ALREADY_REGISTERED)
            }
            if (userRepository.existsByEmail(email)) {
                throw DuplicateEmailException()
            }
            if (userRepository.existsByUsername(username)) {
                throw DuplicateUsernameException()
            }

            val user = userRepository.createUser(
                email = email,
                passwordHash = null,
                username = username
            )
            socialAccountRepository.create(user.id.value, provider, providerUserId)

            // displayName은 소셜 이름
            UserProfileDao.new {
                this.userId = user.id
                this.displayName = (name ?: username).take(Constants.User.MAX_DISPLAY_NAME_LENGTH)
                this.bio = null
                this.avatarUrl = null
                this.location = null
                this.website = null
            }

            user.id.value
        }

        // 포인트 계정 자동 생성
        pointService.createPointAccount(userId)

        // 실패해도 가입 진행
        if (picture != null) {
            fileUploadService.uploadAvatarFromUrl(userId, picture)?.let { avatar ->
                query {
                    UserProfileDao.find { UserProfileTable.userId eq userId }.firstOrNull()?.apply {
                        avatarUrl = avatar.avatarUrl
                        avatarThumbUrl = avatar.avatarThumbUrl
                    }
                }
            }
        }

        return establishSession(userId, request.deviceId, request.fid, userAgent, ipAddress)
    }

    private fun parseProvider(value: String): SocialProvider =
        SocialProvider.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw InvalidInputException(Errors.User.UNSUPPORTED_SOCIAL_PROVIDER)

    /** 추천 username 생성 */
    private suspend fun suggestUsername(email: String): String {
        val base = email.substringBefore("@")  // query 트랜잭션 스코프 내에서만 호출
            .lowercase()
            .filter { it.isLetterOrDigit() || it == '_' }
            .take(Constants.User.MAX_USERNAME_LENGTH)

        val first = padToMinUsernameLength(base)
        if (!userRepository.existsByUsername(first)) return first

        repeat(5) {
            val suffix = randomDigits(4)
            val candidate = padToMinUsernameLength(
                base.take(Constants.User.MAX_USERNAME_LENGTH - suffix.length) + suffix
            )
            if (!userRepository.existsByUsername(candidate)) return candidate
        }

        // 사실상 도달하지 않는 fallback
        return "user" + randomDigits(8)
    }

    private fun padToMinUsernameLength(value: String): String =
        if (value.length >= Constants.User.MIN_USERNAME_LENGTH) value
        else value + randomDigits(Constants.User.MIN_USERNAME_LENGTH - value.length)

    private fun randomDigits(count: Int): String =
        buildString { repeat(count) { append(secureRandom.nextInt(10)) } }

    // 토큰 재발급
    suspend fun refreshAccessToken(refreshToken: String): RefreshTokenResponse {
        val snapshot = query {  // 실패 경로는 별도 트랜잭션 커밋
            refreshTokenRepository.findByToken(refreshToken)?.let {
                RefreshTokenSnapshot(it.sessionId, it.userId, it.expiresAt, it.isRevoked, it.rotatedAt)
            }
        } ?: throw InvalidInputException(Errors.User.INVALID_REFRESH_TOKEN)

        if (snapshot.expiresAt < nowUtc()) {
            // 만료 시 FCM 토큰 정리 + 소켓 종료
            query { clearDeviceTokenForSession(snapshot.sessionId) }
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
                        clearDeviceTokenForSession(snapshot.sessionId)
                    }
                    webSocketManager.disconnectSession(snapshot.sessionId)
                }
                throw TokenExpiredException(Errors.User.REVOKED_REFRESH_TOKEN)
            }
            // grace 안이면 재발급
        }

        // 값만 추출
        val isActive = query {
            userRepository.findUserById(snapshot.userId)?.isActive
                ?: throw InvalidInputException(Errors.User.INVALID_REFRESH_TOKEN)
        }
        if (!isActive) {
            throw ForbiddenException(Errors.User.ACCOUNT_DISABLED)
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
            refreshTokenRepository.markRotated(refreshToken, nowUtc())
            val (newAccessToken, newRefreshToken) = issueTokens(user, snapshot.sessionId)
            RefreshTokenResponse(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                expiresIn = Constants.User.ACCESS_TOKEN_VALIDITY_MINUTES * 60L
            )
        }
    }

    private suspend fun issueTokens(
        user: UserDao,
        sessionId: String
    ): Pair<String, String> {
        val accessToken = JwtConfig.makeAccessToken(  // login/refreshAccessToken 공용
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

    /** 로그아웃 (세션 토큰 무효화, FCM 토큰 정리) */
    suspend fun logout(sessionId: String) {
        query {
            refreshTokenRepository.revokeBySession(sessionId)
            clearDeviceTokenForSession(sessionId)
        }
        // 열린 소켓으로 알림 새는 것 방지
        webSocketManager.disconnectSession(sessionId)
    }

    private suspend fun clearDeviceTokenForSession(sessionId: String) {
        userSessionRepository.findSessionById(sessionId)?.deviceId?.let { deviceId ->  // 세션 무효화 시 호출
            userRepository.deleteDeviceToken(deviceId)
        }
    }

    /** 비밀번호 재설정 (재설정 인증 코드 발송) */
    suspend fun requestPasswordReset(email: String): String {
        val (userInfo, resetCode) = query {
            val foundUser = userRepository.findUserByEmail(email)
                ?: throw UserNotFoundByEmailException(email)

            // 간편로그인 전용 계정은 비밀번호 없음
            if (foundUser.passwordHash == null) {
                throw SocialOnlyAccountException()
            }

            val lastSentAt = foundUser.passwordResetCodeLastSentAt

            val cooldownThreshold = Clock.System.now()
                .minus(Constants.User.RESET_CODE_RESEND_COOLDOWN_SECONDS.seconds)
                .toLocalDateTime(TimeZone.UTC)
            if (lastSentAt != null && lastSentAt > cooldownThreshold) {
                throw TooManyRequestsException(Errors.User.RESET_CODE_RESEND_TOO_SOON)
            }

            // 시간당 전송 상한 확인
            val windowThreshold = Clock.System.now().minus(1.hours).toLocalDateTime(TimeZone.UTC)
            val sentCount = if (lastSentAt != null && lastSentAt > windowThreshold) {
                foundUser.passwordResetCodeSentCount
            } else 0
            if (sentCount >= Constants.User.RESET_CODE_MAX_SENDS_PER_HOUR) {
                throw TooManyRequestsException(Errors.User.RESET_CODE_SEND_LIMIT_EXCEEDED)  // 시간당 상한 초과 시 429
            }

            val code = generateVerificationCode()
            val codeExpiry = Clock.System.now()
                .plus(Constants.User.RESET_CODE_VALIDITY_MINUTES.minutes)
                .toLocalDateTime(TimeZone.UTC)

            userRepository.savePasswordResetCode(
                userId = foundUser.id.value,
                codeHash = TokenHasher.hash(code),
                expiry = codeExpiry,
                sentCount = sentCount + 1,
                lastSentAt = nowUtc()
            )

            Pair(UserEmailInfo(foundUser.email, foundUser.username), code)
        }

        val result = emailService.sendPasswordResetEmail(
            email = userInfo.email,
            username = userInfo.username,
            code = resetCode
        )

        if (result.isFailure) {
            throw EmailSendException(Errors.User.EMAIL_SEND_FAILED)
        }

        return Messages.Auth.PASSWORD_RESET_EMAIL_SENT
    }

    /** 코드 검증 후 재설정 토큰 발급 */
    suspend fun verifyResetCode(email: String, code: String): String {
        val resetToken = query {
            val user = userRepository.findUserByEmail(email)
                ?: throw UserNotFoundByEmailException(email)

            val storedHash = user.passwordResetCode
                ?: throw InvalidInputException(Errors.User.RESET_CODE_NOT_FOUND)

            val expiry = user.passwordResetCodeExpiry
            if (expiry == null || expiry < nowUtc()) {
                throw TokenExpiredException(Errors.User.RESET_CODE_EXPIRED)
            }

            if (user.passwordResetCodeAttempts >= Constants.User.RESET_CODE_MAX_ATTEMPTS) {
                throw InvalidInputException(Errors.User.RESET_CODE_ATTEMPTS_EXCEEDED)
            }

            // 실패 횟수 커밋 후 throw
            if (TokenHasher.hash(code.uppercase()) != storedHash) {
                val failedAttempts = user.passwordResetCodeAttempts + 1
                userRepository.incrementPasswordResetCodeAttempts(user.id.value)
                if (failedAttempts >= Constants.User.RESET_CODE_MAX_ATTEMPTS) {
                    userRepository.clearPasswordResetCode(user.id.value)
                }
                return@query null
            }

            // 코드 폐기 후 재설정 토큰 발급
            userRepository.clearPasswordResetCode(user.id.value)

            val token = UUID.randomUUID().toString()
            val tokenExpiry = Clock.System.now()
                .plus(Constants.User.RESET_TOKEN_VALIDITY_MINUTES.minutes)
                .toLocalDateTime(TimeZone.UTC)
            userRepository.setPasswordResetToken(
                userId = user.id.value,
                token = token,
                expiry = tokenExpiry
            )

            token
        }

        return resetToken ?: throw InvalidInputException(Errors.User.RESET_CODE_INVALID)
    }

    private fun generateVerificationCode(): String =
        (1..Constants.User.VERIFICATION_CODE_LENGTH)
            .map { Constants.User.VERIFICATION_CODE_CHARSET[secureRandom.nextInt(Constants.User.VERIFICATION_CODE_CHARSET.length)] }  // 혼동 문자 제외 charset
            .joinToString("")

    /** 비밀번호 변경 */
    suspend fun resetPassword(token: String, newPassword: String): String {
        ValidationUtils.validatePassword(newPassword, "새 비밀번호")

        val userId = query {
            val user = userRepository.findByPasswordResetToken(token)
                ?: throw InvalidInputException(Errors.User.RESET_TOKEN_INVALID)

            val now = nowUtc()
            if (user.passwordResetTokenExpiry == null || user.passwordResetTokenExpiry!! < now) {
                throw TokenExpiredException(Errors.User.RESET_TOKEN_EXPIRED)
            }

            val passwordHash = PasswordManager.hashPassword(newPassword)
            userRepository.updatePassword(user.id.value, passwordHash)

            userRepository.clearPasswordResetToken(user.id.value)
            userRepository.clearPasswordResetCode(user.id.value)

            // 전 기기 토큰 무효화
            refreshTokenRepository.revokeAllByUser(user.id.value)
            user.id.value
        }

        // 열린 소켓도 종료
        webSocketManager.disconnectUser(userId)

        return Messages.Auth.PASSWORD_RESET_SUCCESS
    }

    // 내 계정
    suspend fun getCurrentUser(userId: Int): UserResponse = query {
        val user = userRepository.findUserById(userId)
            ?: throw UserNotFoundException(userId)
        user.toUserResponse()
    }

    /** 비밀번호 변경 (다른 기기 토큰 무효화) */
    suspend fun changePassword(userId: Int, sessionId: String?, request: ChangePasswordRequest): String {
        ValidationUtils.validatePassword(request.newPassword, "새 비밀번호")

        query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            // 간편로그인 전용 계정은 비밀번호 변경 불가
            val passwordHash = user.passwordHash ?: throw SocialOnlyAccountException()

            if (!PasswordManager.verifyPassword(request.currentPassword, passwordHash)) {
                throw InvalidCredentialsException(Errors.User.CURRENT_PASSWORD_MISMATCH)
            }

            if (request.newPassword == request.currentPassword) {
                throw InvalidPasswordException(Errors.User.NEW_PASSWORD_SAME_AS_CURRENT)
            }

            userRepository.updatePassword(userId, PasswordManager.hashPassword(request.newPassword))

            if (sessionId != null) {
                refreshTokenRepository.revokeAllByUserExcept(userId, sessionId)
            } else {
                refreshTokenRepository.revokeAllByUser(userId)
            }
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
            socialAccountRepository.deleteAllByUserId(userId)

            // 전 기기 refresh token/세션/FCM 정리
            refreshTokenRepository.revokeAllByUser(userId)
            userSessionRepository.findUserSessions(userId).forEach { session ->
                userSessionRepository.deleteSession(session.sessionId)
            }
            userRepository.deleteAllDeviceTokensForUser(userId)

            // 결제·개인데이터·크리에이터 유산 정리
            val cleanupResult = accountCleanupService.cleanupInTransaction(userId)

            Pair(avatarUrls, cleanupResult)
        }

        // 커밋 뒤 소켓 종료
        webSocketManager.disconnectUser(userId)

        // 캐시 무효화
        cacheService.delete(CacheKeys.profile(userId))
        cacheService.delete(CacheKeys.user(userId))
        cacheService.deletePattern(CacheKeys.Patterns.creatorStore(userId))
        cleanup.deletedProductIds.forEach { productId ->
            cacheService.deletePattern(CacheKeys.Patterns.product(productId))
            cacheService.deletePattern(CacheKeys.Patterns.relatedProducts(productId))
        }

        // 시드 공용 파일은 보존
        (oldAvatarUrls + cleanup.fileUrlsToDelete)
            .filterNot { fileUploadService.isSharedSeedFile(it) }
            .forEach { fileUploadService.deleteFileIfSupabase(it) }

        return Messages.Auth.ACCOUNT_DELETED
    }
}
