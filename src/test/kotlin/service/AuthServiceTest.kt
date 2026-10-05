package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.email.EmailService
import com.ninezero.core.security.PasswordManager
import com.ninezero.features.point.data.PointHistoryRepositoryImpl
import com.ninezero.features.point.data.PointRepositoryImpl
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.user.data.RefreshTokenRepositoryImpl
import com.ninezero.features.user.data.SocialAccountRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.features.user.data.UserSessionRepositoryImpl
import com.ninezero.features.user.domain.AuthService
import com.ninezero.features.user.presentation.models.request.LoginRequest
import com.ninezero.features.user.presentation.models.request.RegisterRequest
import com.ninezero.features.user.presentation.models.request.VerifyEmailCodeRequest
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.minusDays
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthServiceTest {
    private lateinit var authService: AuthService
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var userSessionRepository: UserSessionRepositoryImpl
    private lateinit var refreshTokenRepository: RefreshTokenRepositoryImpl
    private lateinit var emailService: EmailService
    private lateinit var pointService: PointService

    // 발송 mock에 전달된 인증 코드 캡처(가입·비밀번호 재설정)
    private val signupCodeSlot = slot<String>()
    private val passwordResetCodeSlot = slot<String>()

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            userRepository = UserRepositoryImpl()
            userSessionRepository = UserSessionRepositoryImpl()
            refreshTokenRepository = RefreshTokenRepositoryImpl()

            emailService = mockk(relaxed = true)

            pointService = PointService(
                pointRepository = PointRepositoryImpl(),
                pointHistoryRepository = PointHistoryRepositoryImpl(),
                notificationService = mockk(relaxed = true),
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            )

            authService = AuthService(
                userRepository = userRepository,
                userSessionRepository = userSessionRepository,
                refreshTokenRepository = refreshTokenRepository,
                emailService = emailService,
                pointService = pointService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
                socialAccountRepository = SocialAccountRepositoryImpl(),
                socialTokenVerifiers = emptyList(),
                fileUploadService = mockk(relaxed = true),
                cacheService = CacheService(),
                accountCleanupService = TestFixtures.accountCleanupService(),
                webSocketManager = mockk(relaxed = true)
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()

            // 발송 성공 기본값
            coEvery {
                emailService.sendVerificationEmail(any(), any(), capture(signupCodeSlot))
            } returns Result.success(Unit)
            coEvery {
                emailService.sendPasswordResetEmail(any(), any(), capture(passwordResetCodeSlot))
            } returns Result.success(Unit)
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    // ===== 회원가입 테스트 =====

    @Test
    fun `회원가입 성공`() {
        runBlocking {
            val request = RegisterRequest(
                email = "test@example.com",
                password = "Password123!",
                username = "testuser"
            )

            val response = authService.register(request)

            assertEquals("test@example.com", response.email)
            assertEquals("testuser", response.username)

            // 계정은 코드 검증 후에 생성된다
            query {
                assertNull(userRepository.findUserByEmail("test@example.com"), "코드 검증 전에는 계정이 없어야 합니다")
                assertNotNull(userRepository.findPendingSignupByEmail("test@example.com"), "대기 가입이 저장되어야 합니다")
            }
        }
    }

    @Test
    fun `회원가입 실패 - 약한 비밀번호`() {
        runBlocking {
            val request = RegisterRequest(
                email = "test@example.com",
                password = "weak",
                username = "testuser"
            )

            assertFailsWith<InvalidPasswordException> {
                authService.register(request)
            }
        }
    }

    @Test
    fun `회원가입 시 포인트 계정 자동 생성`() {
        runBlocking {
            val request = RegisterRequest(
                email = "test@example.com",
                password = "Password123!",
                username = "testuser"
            )

            authService.register(request)
            verifyCode("test@example.com", signupCodeSlot.captured)

            // 사용자 조회 및 포인트 잔액 확인
            val user = query {
                userRepository.findUserByEmail("test@example.com")
            }
            assertNotNull(user)

            val balanceResponse = pointService.getBalance(user.id.value)
            assertEquals(0, balanceResponse.balance.toBigDecimal().compareTo(BigDecimal.ZERO), "포인트 잔액이 0이어야 합니다")
        }
    }

    @Test
    fun `회원가입 - 대문자 이메일·username은 소문자로 저장되고 소문자 이메일로 로그인`() {
        runBlocking {
            val response = authService.register(
                RegisterRequest(email = "Test@Example.COM", password = "Password123!", username = "TestUser")
            )
            assertEquals("test@example.com", response.email)
            assertEquals("testuser", response.username)

            // 인증 코드 확인도 대소문자 무관
            verifyCode("TEST@example.com", signupCodeSlot.captured)

            val auth = authService.login(
                LoginRequest(email = "test@example.com", password = "Password123!"),
                userAgent = "Mozilla/5.0",
                ipAddress = "127.0.0.1"
            )
            assertTrue(auth.accessToken.isNotBlank())
            assertEquals("test@example.com", auth.user.email)
            assertEquals("testuser", auth.user.username)
        }
    }

    @Test
    fun `회원가입 실패 - 대소문자만 다른 username`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test1@example.com", username = "testuser")

            assertFailsWith<DuplicateUsernameException> {
                authService.register(
                    RegisterRequest(email = "test2@example.com", password = "Password456!", username = "TestUser")
                )
            }
        }
    }

    // ===== 로그인 테스트 =====

    @Test
    fun `로그인 실패 - 잘못된 이메일`() {
        runBlocking {
            val loginRequest = LoginRequest(
                email = "notexist@example.com",
                password = "Password123!"
            )

            assertFailsWith<InvalidCredentialsException> {
                authService.login(
                    loginRequest,
                    userAgent = "Mozilla/5.0",
                    ipAddress = "127.0.0.1"
                )
            }
        }
    }

    @Test
    fun `로그인 실패 - 잘못된 비밀번호`() {
        runBlocking {
            TestFixtures.createTestUser(
                email = "test@example.com",
                password = "Password123!"
            )

            val loginRequest = LoginRequest(
                email = "test@example.com",
                password = "WrongPassword!"
            )

            assertFailsWith<InvalidCredentialsException> {
                authService.login(
                    loginRequest,
                    userAgent = "Mozilla/5.0",
                    ipAddress = "127.0.0.1"
                )
            }
        }
    }

    @Test
    fun `로그인 실패 - 비활성화된 계정`() {
        runBlocking {
            val user = TestFixtures.createTestUser(
                email = "test@example.com",
                password = "Password123!"
            )

            // 계정 비활성화
            query {
                user.isActive = false
            }

            val loginRequest = LoginRequest(
                email = "test@example.com",
                password = "Password123!"
            )

            assertFailsWith<ForbiddenException> {
                authService.login(
                    loginRequest,
                    userAgent = "Mozilla/5.0",
                    ipAddress = "127.0.0.1"
                )
            }
        }
    }

    // ===== 인증 코드 테스트 =====

    /** 가입 요청 후 발송 mock에서 캡처한 인증 코드 반환 */
    private suspend fun registerAndCaptureCode(
        email: String = "test@example.com",
        username: String = "testuser"
    ): String {
        authService.register(RegisterRequest(email = email, password = "Password123!", username = username))
        return signupCodeSlot.captured
    }

    private suspend fun verifyCode(email: String, code: String) = authService.verifyEmailCode(
        VerifyEmailCodeRequest(email = email, code = code),
        userAgent = "Mozilla/5.0",
        ipAddress = "127.0.0.1"
    )

    @Test
    fun `인증 코드 검증 성공 - 계정 생성 + 세션 발급`() {
        runBlocking {
            val code = registerAndCaptureCode()

            val response = verifyCode("test@example.com", code)

            assertTrue(response.accessToken.isNotBlank(), "검증 성공 시 곧바로 로그인 세션이 발급되어야 합니다")
            assertEquals("test@example.com", response.user.email)

            query {
                assertNotNull(userRepository.findUserByEmail("test@example.com"), "코드 검증 후 계정이 생성되어야 합니다")
                assertNull(userRepository.findPendingSignupByEmail("test@example.com"), "대기 가입은 계정 생성과 함께 삭제되어야 합니다")
            }
        }
    }

    @Test
    fun `인증 코드 검증 실패 - 잘못된 코드`() {
        runBlocking {
            val code = registerAndCaptureCode()
            val wrongCode = if (code == "AAAAAA") "BBBBBB" else "AAAAAA"

            assertFailsWith<InvalidInputException> {
                verifyCode("test@example.com", wrongCode)
            }

            query {
                assertNull(userRepository.findUserByEmail("test@example.com"), "실패 시 계정이 생성되면 안 됩니다")
                assertEquals(1, userRepository.findPendingSignupByEmail("test@example.com")!!.attempts, "실패 횟수가 커밋되어야 합니다")
            }
        }
    }

    @Test
    fun `인증 코드 검증 실패 - 만료된 코드`() {
        runBlocking {
            val code = registerAndCaptureCode()

            query {
                userRepository.findPendingSignupByEmail("test@example.com")!!.codeExpiry = nowUtc().minusDays(1)
            }

            assertFailsWith<TokenExpiredException> {
                verifyCode("test@example.com", code)
            }
        }
    }

    @Test
    fun `인증 코드 재발송 성공`() {
        runBlocking {
            registerAndCaptureCode()

            // 쿨다운(60초)이 지난 상태로 만든다
            query {
                userRepository.findPendingSignupByEmail("test@example.com")!!.lastSentAt = nowUtc().minusDays(1)
            }

            authService.resendVerificationEmail("test@example.com")

            // 재발송한 새 코드로 가입 완료
            val response = verifyCode("test@example.com", signupCodeSlot.captured)
            assertEquals("test@example.com", response.user.email)
        }
    }

    @Test
    fun `인증 코드 재발송 실패 - 재전송 쿨다운`() {
        runBlocking {
            registerAndCaptureCode()

            assertFailsWith<TooManyRequestsException> {
                authService.resendVerificationEmail("test@example.com")
            }
        }
    }

    // ===== 비밀번호 재설정 테스트 =====

    @Test
    fun `비밀번호 재설정 실패 - 5회 틀리면 시도 횟수 초과`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test@example.com")
            authService.requestPasswordReset("test@example.com")
            val code = passwordResetCodeSlot.captured
            val wrongCode = if (code == "AAAAAA") "BBBBBB" else "AAAAAA"

            repeat(Constants.User.RESET_CODE_MAX_ATTEMPTS) {
                assertFailsWith<InvalidInputException> {
                    authService.verifyResetCode("test@example.com", wrongCode)
                }
            }

            // 맞는 코드여도 막히고 초과 문구
            val error = assertFailsWith<InvalidInputException> {
                authService.verifyResetCode("test@example.com", code)
            }
            assertEquals(Errors.User.RESET_CODE_ATTEMPTS_EXCEEDED, error.message)
        }
    }

    @Test
    fun `비밀번호 재설정 요청 실패 - 존재하지 않는 이메일`() {
        runBlocking {
            assertFailsWith<UserNotFoundByEmailException> {
                authService.requestPasswordReset("notexist@example.com")
            }
        }
    }

    @Test
    fun `비밀번호 재설정 성공`() {
        runBlocking {
            TestFixtures.createTestUser(
                email = "test@example.com",
                password = "OldPassword123!"
            )
            authService.login(
                LoginRequest(email = "test@example.com", password = "OldPassword123!", fid = "fid-1", deviceId = "device-1"),
                "TestAgent",
                "127.0.0.1"
            )

            // 인증 코드 발송 → 발송 mock에서 코드 캡처
            authService.requestPasswordReset("test@example.com")
            val code = passwordResetCodeSlot.captured

            // 코드 검증 → 재설정 토큰 발급
            val resetToken = authService.verifyResetCode("test@example.com", code).resetToken
            val storedToken = query { userRepository.findUserByEmail("test@example.com")!!.passwordResetToken }
            assertNotEquals(resetToken, storedToken, "재설정 토큰은 원문으로 저장하지 않는다")

            authService.resetPassword(resetToken, "NewPassword123!")

            // 새 비밀번호로 확인
            val updatedUser = query {
                userRepository.findUserByEmail("test@example.com")
            }
            assertTrue(
                PasswordManager.verifyPassword("NewPassword123!", updatedUser!!.passwordHash!!),
                "새 비밀번호가 설정되어야 합니다"
            )

            // 전 기기 로그아웃은 기기 목록·푸시 토큰까지
            query {
                assertTrue(userSessionRepository.findUserSessions(updatedUser.id.value).isEmpty())
                assertTrue(userRepository.getDeviceFidsForUser(updatedUser.id.value).isEmpty())
            }
        }
    }

    @Test
    fun `비밀번호 재설정 요청 실패 - 재전송 쿨다운`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test@example.com")

            authService.requestPasswordReset("test@example.com")

            // 쿨다운(60초) 내 재요청 → 429
            assertFailsWith<TooManyRequestsException> {
                authService.requestPasswordReset("test@example.com")
            }
        }
    }

    @Test
    fun `메일 발송이 실패하면 쿨다운 없이 바로 다시 보낼 수 있다`() {
        runBlocking {
            val failed = Result.failure<Unit>(RuntimeException("발송 실패"))
            coEvery { emailService.sendVerificationEmail(any(), any(), capture(signupCodeSlot)) } returnsMany
                listOf(failed, Result.success(Unit), failed, Result.success(Unit))
            coEvery { emailService.sendPasswordResetEmail(any(), any(), capture(passwordResetCodeSlot)) } returnsMany
                listOf(failed, Result.success(Unit))

            // 가입 코드
            val request = RegisterRequest(email = "test@example.com", password = "Password123!", username = "testuser")
            assertFailsWith<EmailSendException> { authService.register(request) }
            authService.register(request)

            // 가입 코드 재발송
            query {
                userRepository.findPendingSignupByEmail("test@example.com")!!.lastSentAt = nowUtc().minusDays(1)
            }
            assertFailsWith<EmailSendException> { authService.resendVerificationEmail("test@example.com") }
            authService.resendVerificationEmail("test@example.com")

            // 비밀번호 재설정 코드
            TestFixtures.createTestUser(email = "reset@example.com", username = "resetuser")
            assertFailsWith<EmailSendException> { authService.requestPasswordReset("reset@example.com") }
            authService.requestPasswordReset("reset@example.com")
        }
    }

    @Test
    fun `비밀번호 재설정 - 이메일 대소문자 무관`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test@example.com", password = "OldPassword123!")

            authService.requestPasswordReset("TEST@Example.com")
            val resetToken = authService.verifyResetCode("Test@example.COM", passwordResetCodeSlot.captured).resetToken
            authService.resetPassword(resetToken, "NewPassword123!")

            // 대소문자가 달라도 끝까지 같은 계정의 비밀번호가 바뀜
            val updatedUser = query { userRepository.findUserByEmail("test@example.com") }
            assertTrue(PasswordManager.verifyPassword("NewPassword123!", updatedUser!!.passwordHash!!))
        }
    }

    @Test
    fun `만료된 지 하루가 지난 가입 대기만 정리한다`() {
        runBlocking {
            val now = nowUtc()
            query {
                userRepository.upsertPendingSignup(
                    email = "old@example.com", username = "olduser", passwordHash = "hash", codeHash = "code",
                    codeExpiry = now, expiresAt = now.minusDays(2), sentCount = 1, lastSentAt = now
                )
                userRepository.upsertPendingSignup(
                    email = "recent@example.com", username = "recentuser", passwordHash = "hash", codeHash = "code",
                    codeExpiry = now, expiresAt = now, sentCount = 1, lastSentAt = now
                )
            }

            val deleted = query { userRepository.deleteExpiredPendingSignups(now.minusDays(1)) }

            assertEquals(1, deleted)
            assertNull(query { userRepository.findPendingSignupByEmail("old@example.com") })
            assertNotNull(query { userRepository.findPendingSignupByEmail("recent@example.com") })
        }
    }

    // ===== 기타 테스트 =====

    @Test
    fun `현재 사용자 정보 조회 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser(
                email = "test@example.com",
                username = "testuser"
            )

            val response = authService.getCurrentUser(user.id.value)

            assertEquals("test@example.com", response.email)
            assertEquals("testuser", response.username)
        }
    }
}
