package com.ninezero.service

import com.ninezero.core.cache.CacheService
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AuthService 테스트
 *
 * 테스트 케이스: 21개
 * - 회원가입: 5개
 * - 로그인: 4개
 * - 인증 코드: 6개
 * - 비밀번호 재설정: 5개
 * - 기타: 1개
 */
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

            // EmailService mock
            emailService = mockk(relaxed = true)
            coEvery {
                emailService.sendVerificationEmail(any(), any(), capture(signupCodeSlot))
            } returns Result.success("")

            coEvery {
                emailService.sendPasswordResetEmail(any(), any(), capture(passwordResetCodeSlot))
            } returns Result.success("")

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
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    // ===== 회원가입 테스트 (5개) =====

    @Test
    fun `회원가입 성공`() {
        runBlocking {
            val request = RegisterRequest(
                email = "test@example.com",
                password = "Password123!",
                username = "testuser"
            )

            val response = authService.register(request)

            assertNotNull(response)
            assertEquals("test@example.com", response.email)
            assertEquals("testuser", response.username)
            assertTrue(response.needsVerification, "이메일 인증이 필요합니다")

            // 계정은 코드 검증 후에 생성된다
            query {
                assertNull(userRepository.findUserByEmail("test@example.com"), "코드 검증 전에는 계정이 없어야 합니다")
                assertNotNull(userRepository.findPendingSignupByEmail("test@example.com"), "대기 가입이 저장되어야 합니다")
            }
        }
    }

    @Test
    fun `회원가입 실패 - 중복 이메일`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test@example.com", username = "existing")

            val duplicateRequest = RegisterRequest(
                email = "test@example.com",
                password = "Password456!",
                username = "testuser2"
            )

            assertFailsWith<DuplicateEmailException> {
                authService.register(duplicateRequest)
            }
        }
    }

    @Test
    fun `회원가입 실패 - 중복 username`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test1@example.com", username = "testuser")

            val duplicateRequest = RegisterRequest(
                email = "test2@example.com",
                password = "Password456!",
                username = "testuser"
            )

            assertFailsWith<DuplicateUsernameException> {
                authService.register(duplicateRequest)
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
            assertNotNull(balanceResponse)
            assertEquals(balanceResponse.balance.toBigDecimal().compareTo(BigDecimal.ZERO), 0, "포인트 잔액이 0이어야 합니다")
        }
    }

    // ===== 로그인 테스트 (4개) =====

    @Test
    fun `로그인 성공 - JWT 토큰 발급`() {
        runBlocking {
            TestFixtures.createTestUser(
                email = "test@example.com",
                username = "testuser",
                password = "Password123!"
            )

            val loginRequest = LoginRequest(
                email = "test@example.com",
                password = "Password123!"
            )

            val response = authService.login(
                loginRequest,
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0",
                ipAddress = "127.0.0.1"
            )

            assertNotNull(response)
            assertNotNull(response.accessToken, "JWT 토큰이 발급되어야 합니다")
            assertEquals("test@example.com", response.user.email)
        }
    }

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

    // ===== 인증 코드 테스트 (6개) =====

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

            assertNotNull(response.accessToken, "검증 성공 시 곧바로 로그인 세션이 발급되어야 합니다")
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
    fun `인증 코드 검증 실패 - 진행 중인 가입 요청 없음`() {
        runBlocking {
            assertFailsWith<InvalidInputException> {
                verifyCode("notexist@example.com", "AAAAAA")
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

            val message = authService.resendVerificationEmail("test@example.com")
            assertNotNull(message)
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

    // ===== 비밀번호 재설정 테스트 (5개) =====

    @Test
    fun `비밀번호 재설정 요청 성공`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test@example.com")

            val message = authService.requestPasswordReset("test@example.com")
            assertNotNull(message)
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

            // 인증 코드 발송 → 발송 mock에서 코드 캡처
            authService.requestPasswordReset("test@example.com")
            val code = passwordResetCodeSlot.captured

            // 코드 검증 → 재설정 토큰 발급
            val resetToken = authService.verifyResetCode("test@example.com", code)
            assertNotNull(resetToken)

            val message = authService.resetPassword(resetToken, "NewPassword123!")
            assertNotNull(message)

            // 새 비밀번호로 확인
            val updatedUser = query {
                userRepository.findUserByEmail("test@example.com")
            }
            assertTrue(
                PasswordManager.verifyPassword("NewPassword123!", updatedUser!!.passwordHash!!),
                "새 비밀번호가 설정되어야 합니다"
            )
        }
    }

    @Test
    fun `비밀번호 재설정 실패 - 잘못된 인증 코드`() {
        runBlocking {
            TestFixtures.createTestUser(email = "test@example.com")

            authService.requestPasswordReset("test@example.com")

            // 캡처된 코드와 확실히 다른 코드로 검증
            val wrongCode = if (passwordResetCodeSlot.captured == "AAAAAA") "BBBBBB" else "AAAAAA"

            assertFailsWith<InvalidInputException> {
                authService.verifyResetCode("test@example.com", wrongCode)
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

    // ===== 기타 테스트 (1개) =====

    @Test
    fun `현재 사용자 정보 조회 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser(
                email = "test@example.com",
                username = "testuser"
            )

            val response = authService.getCurrentUser(user.id.value)

            assertNotNull(response)
            assertEquals("test@example.com", response.email)
            assertEquals("testuser", response.username)
        }
    }
}
