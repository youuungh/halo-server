package com.ninezero.features.user.domain

import com.auth0.jwt.exceptions.JWTVerificationException
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.SocialProvider
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.CommonValidation
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.user.UserProfileDao
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.security.JwtConfig
import com.ninezero.core.storage.FileUploadService
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.user.data.SocialAccountRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.domain.oauth.SocialTokenVerifier
import com.ninezero.features.user.presentation.models.request.SocialCompleteRequest
import com.ninezero.features.user.presentation.models.request.SocialLoginRequest
import com.ninezero.features.user.presentation.models.response.AuthResponse
import com.ninezero.features.user.presentation.models.response.SocialLoginResponse
import org.jetbrains.exposed.v1.core.eq

/** 간편로그인과 가입 확정 */
class SocialAuthService(
    private val userRepository: UserRepository,
    private val socialAccountRepository: SocialAccountRepository,
    private val pointService: PointService,
    private val fileUploadService: FileUploadService,
    private val userSessionService: UserSessionService,
    private val socialTokenVerifiers: List<SocialTokenVerifier>
) {
    // =====  간편로그인 =====

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
            val auth = userSessionService.establishSession(linkedUserId, request.deviceId, request.fid, userAgent, ipAddress)
            return SocialLoginResponse(needsUsername = false, auth = auth)
        }

        val email = identity.email?.let(CommonValidation::normalizeEmail)
            ?: throw InvalidInputException(Errors.User.SOCIAL_EMAIL_REQUIRED)

        // 미검증 이메일은 신규가입 불가
        if (!identity.emailVerified) {
            throw ForbiddenException(Errors.User.SOCIAL_EMAIL_NOT_VERIFIED)
        }

        // 같은 이메일 계정 거부, 자동 연동 재도입 금지
        if (query { userRepository.existsByEmail(email) }) {
            throw DuplicateEmailException()
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
        val decoded = try {
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

        val username = UserValidation.normalizeUsername(request.username)
        UserValidation.validateUsername(username)

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

            pointService.createPointAccountInTransaction(user.id.value)
            user.id.value
        }

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

        return userSessionService.establishSession(userId, request.deviceId, request.fid, userAgent, ipAddress)
    }

    private fun parseProvider(value: String): SocialProvider =
        SocialProvider.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw InvalidInputException(Errors.User.UNSUPPORTED_SOCIAL_PROVIDER)

    /** 추천 username 생성, 트랜잭션 안에서만 호출 */
    private suspend fun suggestUsername(email: String): String {
        val base = email.substringBefore("@")
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
        return "user${randomDigits(8)}"
    }

    private fun padToMinUsernameLength(value: String): String =
        if (value.length >= Constants.User.MIN_USERNAME_LENGTH) value
        else value + randomDigits(Constants.User.MIN_USERNAME_LENGTH - value.length)
}
