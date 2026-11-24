package com.ninezero.features.user.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.email.EmailService
import com.ninezero.core.security.PasswordHasher
import com.ninezero.core.security.TokenHasher
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.response.VerifyResetCodeResponse
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** 비밀번호 재설정 코드·토큰 흐름 */
class PasswordResetService(
    private val userRepository: UserRepository,
    private val emailService: EmailService,
    private val userSessionService: UserSessionService,
    private val webSocketManager: WebSocketManager
) {
    private data class UserEmailInfo(val userId: Int, val email: String, val username: String)

    /** 비밀번호 재설정 코드 발송 */
    suspend fun requestPasswordReset(email: String): String {
        val (userInfo, resetCode, previousSend) = query {
            val foundUser = userRepository.findUserByEmail(CommonValidation.normalizeEmail(email))
                ?: throw UserNotFoundByEmailException(email)

            // 간편로그인 전용 계정은 비밀번호 없음
            if (foundUser.passwordHash == null) {
                throw SocialOnlyAccountException()
            }

            val lastSentAt = foundUser.passwordResetCodeLastSentAt

            val waitUntil = Clock.System.now()
                .minus(Constants.User.RESET_CODE_RESEND_WAIT_SECONDS.seconds)
                .toLocalDateTime(TimeZone.UTC)
            if (lastSentAt != null && lastSentAt > waitUntil) {
                throw TooManyRequestsException(Errors.User.RESET_CODE_RESEND_TOO_SOON)
            }

            // 시간당 전송 상한 확인
            val windowThreshold = Clock.System.now().minus(1.hours).toLocalDateTime(TimeZone.UTC)
            val sentCount = if (lastSentAt != null && lastSentAt > windowThreshold) {
                foundUser.passwordResetCodeSentCount
            } else 0
            if (sentCount >= Constants.User.RESET_CODE_MAX_SENDS_PER_HOUR) {
                throw TooManyRequestsException(Errors.User.RESET_CODE_SEND_LIMIT_EXCEEDED)
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

            Triple(UserEmailInfo(foundUser.id.value, foundUser.email, foundUser.username), code, CodeSendState(sentCount, lastSentAt))
        }

        val result = emailService.sendPasswordResetEmail(
            email = userInfo.email,
            username = userInfo.username,
            code = resetCode
        )

        if (result.isFailure) {
            // 못 보낸 발송은 쿨다운·시간당 상한에서 제외
            query { userRepository.restorePasswordResetCodeSend(userInfo.userId, previousSend.sentCount, previousSend.lastSentAt) }
            throw EmailSendException(Errors.User.EMAIL_SEND_FAILED)
        }

        return Messages.Auth.PASSWORD_RESET_EMAIL_SENT
    }

    /** 코드 검증 후 재설정 토큰 발급 */
    suspend fun verifyResetCode(email: String, code: String): VerifyResetCodeResponse {
        val resetToken = query {
            val user = userRepository.findUserByEmail(CommonValidation.normalizeEmail(email))
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
                userRepository.incrementPasswordResetCodeAttempts(user.id.value)
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

        return VerifyResetCodeResponse(resetToken ?: throw InvalidInputException(Errors.User.RESET_CODE_INVALID))
    }

    /** 재설정 토큰으로 새 비밀번호 저장 */
    suspend fun resetPassword(token: String, newPassword: String): String {
        CommonValidation.validatePassword(newPassword, "새 비밀번호")

        val userId = query {
            val user = userRepository.findByPasswordResetToken(token)
                ?: throw InvalidInputException(Errors.User.RESET_TOKEN_INVALID)

            val tokenExpiry = user.passwordResetTokenExpiry
            if (tokenExpiry == null || tokenExpiry < nowUtc()) {
                throw TokenExpiredException(Errors.User.RESET_TOKEN_EXPIRED)
            }

            val passwordHash = PasswordHasher.hashPassword(newPassword)
            userRepository.updatePassword(user.id.value, passwordHash)

            userRepository.clearPasswordResetToken(user.id.value)
            userRepository.clearPasswordResetCode(user.id.value)

            // 전 기기 로그아웃
            userSessionService.signOutDevices(user.id.value, keepSessionId = null)
            user.id.value
        }

        // 열린 소켓도 종료
        webSocketManager.disconnectUser(userId)

        return Messages.Auth.PASSWORD_RESET_SUCCESS
    }
}
