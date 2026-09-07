package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.SocialProvider
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.user.UserProfileDao
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.email.EmailService
import com.ninezero.core.security.JwtConfig
import com.ninezero.core.storage.AvatarUploadResult
import com.ninezero.core.storage.FileUploadService
import com.ninezero.features.point.data.PointHistoryRepositoryImpl
import com.ninezero.features.point.data.PointRepositoryImpl
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.user.data.RefreshTokenRepositoryImpl
import com.ninezero.features.user.data.SocialAccountRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.features.user.data.UserSessionRepositoryImpl
import com.ninezero.features.user.domain.AuthService
import com.ninezero.features.user.domain.social.SocialIdentity
import com.ninezero.features.user.domain.social.SocialTokenVerifier
import com.ninezero.features.user.presentation.models.request.LoginRequest
import com.ninezero.features.user.presentation.models.request.SocialCompleteRequest
import com.ninezero.features.user.presentation.models.request.SocialLoginRequest
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 간편로그인(소셜) 테스트
 *
 * 테스트 케이스: 16개
 * - 신규 가입 플로우: 6개
 * - 재로그인 / 자동 연동: 2개
 * - 가입 확정 실패: 4개
 * - 소셜 전용 계정 가드: 2개
 * - 기타: 2개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialLoginTest {
    private lateinit var authService: AuthService
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var socialAccountRepository: SocialAccountRepositoryImpl
    private lateinit var fakeVerifier: FakeSocialTokenVerifier
    private lateinit var fileUploadService: FileUploadService

    private val defaultIdentity = SocialIdentity(
        provider = SocialProvider.GOOGLE,
        providerUserId = "google-sub-1",
        email = "social@example.com",
        emailVerified = true,
        name = "홍길동",
        picture = "https://lh3.googleusercontent.com/a/photo"
    )

    /** 테스트용 검증기 — 설정된 identity를 그대로 반환 */
    private class FakeSocialTokenVerifier(var identity: SocialIdentity) : SocialTokenVerifier {
        override val provider = SocialProvider.GOOGLE
        override suspend fun verify(token: String): SocialIdentity = identity
    }

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            userRepository = UserRepositoryImpl()
            socialAccountRepository = SocialAccountRepositoryImpl()
            fakeVerifier = FakeSocialTokenVerifier(defaultIdentity)
            fileUploadService = mockk()

            val emailService = mockk<EmailService>(relaxed = true)
            val pointService = PointService(
                pointRepository = PointRepositoryImpl(),
                pointHistoryRepository = PointHistoryRepositoryImpl(),
                notificationService = mockk(relaxed = true),
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            )

            authService = AuthService(
                userRepository = userRepository,
                userSessionRepository = UserSessionRepositoryImpl(),
                refreshTokenRepository = RefreshTokenRepositoryImpl(),
                emailService = emailService,
                pointService = pointService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
                socialAccountRepository = socialAccountRepository,
                socialTokenVerifiers = listOf(fakeVerifier),
                fileUploadService = fileUploadService,
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
            fakeVerifier.identity = defaultIdentity
            // 기본: 아바타 가져오기 실패(null) — 개별 테스트에서 필요 시 재정의
            coEvery { fileUploadService.uploadAvatarFromUrl(any(), any()) } returns null
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    private fun socialLoginRequest() = SocialLoginRequest(
        provider = "GOOGLE",
        token = "fake-id-token",
        deviceId = "test-device"
    )

    private suspend fun socialLogin() =
        authService.socialLogin(socialLoginRequest(), "TestAgent", "127.0.0.1")

    private suspend fun socialComplete(signupToken: String, username: String) =
        authService.socialComplete(
            SocialCompleteRequest(signupToken = signupToken, username = username, deviceId = "test-device"),
            "TestAgent",
            "127.0.0.1"
        )

    // ===== 신규 가입 플로우 =====

    @Test
    fun `신규 소셜 로그인 - signupToken 발급, DB 무기록`() {
        runBlocking {
            val response = socialLogin()

            assertTrue(response.needsUsername)
            assertNotNull(response.signupToken)
            assertNull(response.auth)

            val suggested = response.suggestedUsername
            assertNotNull(suggested)
            assertTrue(suggested.length in Constants.User.MIN_USERNAME_LENGTH..Constants.User.MAX_USERNAME_LENGTH)

            // complete 전까지 유저가 생성되지 않아야 한다
            val user = query {
                userRepository.findUserByEmail("social@example.com")
            }
            assertNull(user)
        }
    }

    @Test
    fun `가입 확정 - 유저,프로필,매핑 생성 후 로그인`() {
        runBlocking {
            val loginResponse = socialLogin()
            val auth = socialComplete(loginResponse.signupToken!!, "socialuser")

            assertNotNull(auth.accessToken)
            assertNotNull(auth.refreshToken)
            assertEquals("socialuser", auth.user.username)

            query {
                val user = userRepository.findUserByEmail("social@example.com")
                assertNotNull(user)
                assertNull(user.passwordHash, "소셜 전용 계정은 비밀번호가 없어야 한다")

                val mappedUserId = socialAccountRepository.findUserIdByProvider(SocialProvider.GOOGLE, "google-sub-1")
                assertEquals(user.id.value, mappedUserId)

                val profile = UserProfileDao.find { UserProfileTable.userId eq user.id.value }.firstOrNull()
                assertNotNull(profile)
                assertEquals("홍길동", profile.displayName, "displayName은 소셜 프로필 이름으로 시작해야 한다")
            }
        }
    }

    @Test
    fun `가입 확정 - 소셜 프로필 사진을 아바타로 저장`() {
        runBlocking {
            coEvery { fileUploadService.uploadAvatarFromUrl(any(), any()) } returns AvatarUploadResult(
                avatarUrl = "https://cdn.example.com/avatar.webp",
                avatarThumbUrl = "https://cdn.example.com/avatar_thumb.webp"
            )

            val loginResponse = socialLogin()
            val auth = socialComplete(loginResponse.signupToken!!, "socialuser")

            query {
                val profile = UserProfileDao.find { UserProfileTable.userId eq auth.user.id }.firstOrNull()
                assertNotNull(profile)
                assertEquals("https://cdn.example.com/avatar.webp", profile.avatarUrl)
                assertEquals("https://cdn.example.com/avatar_thumb.webp", profile.avatarThumbUrl)
            }
        }
    }

    @Test
    fun `가입 확정 - 아바타 가져오기 실패해도 가입 진행`() {
        runBlocking {
            // beforeEach 기본 stub = null(실패)
            val loginResponse = socialLogin()
            val auth = socialComplete(loginResponse.signupToken!!, "socialuser")

            assertNotNull(auth.accessToken)
            query {
                val profile = UserProfileDao.find { UserProfileTable.userId eq auth.user.id }.firstOrNull()
                assertNotNull(profile)
                assertNull(profile.avatarUrl, "실패 시 기본 아바타(null)로 시작해야 한다")
            }
        }
    }

    @Test
    fun `가입 확정 - 소셜 이름이 없으면 username을 displayName으로`() {
        runBlocking {
            fakeVerifier.identity = defaultIdentity.copy(name = null)

            val loginResponse = socialLogin()
            val auth = socialComplete(loginResponse.signupToken!!, "socialuser")

            query {
                val profile = UserProfileDao.find { UserProfileTable.userId eq auth.user.id }.firstOrNull()
                assertNotNull(profile)
                assertEquals("socialuser", profile.displayName)
            }
        }
    }

    @Test
    fun `신규 소셜 로그인 - 미검증 이메일이면 거부`() {
        runBlocking {
            fakeVerifier.identity = defaultIdentity.copy(emailVerified = false)

            assertFailsWith<ForbiddenException> { socialLogin() }
        }
    }

    // ===== 재로그인 / 자동 연동 =====

    @Test
    fun `가입 확정 후 재로그인 - 기존 매핑으로 바로 로그인`() {
        runBlocking {
            val first = socialLogin()
            socialComplete(first.signupToken!!, "socialuser")

            val second = socialLogin()

            assertTrue(!second.needsUsername)
            assertNotNull(second.auth)
            assertEquals("socialuser", second.auth!!.user.username)
        }
    }

    @Test
    fun `이메일 매칭 - 인증된 기존 계정에 자동 연동`() {
        runBlocking {
            val existing = TestFixtures.createTestUser(
                email = "social@example.com",
                username = "emailuser"
            )

            val response = socialLogin()

            assertTrue(!response.needsUsername)
            assertNotNull(response.auth)
            assertEquals("emailuser", response.auth!!.user.username)

            val mappedUserId = query {
                socialAccountRepository.findUserIdByProvider(SocialProvider.GOOGLE, "google-sub-1")
            }
            assertEquals(existing.id.value, mappedUserId)
        }
    }

    // ===== 가입 확정 실패 =====

    @Test
    fun `가입 확정 실패 - username 중복이면 유저 미생성`() {
        runBlocking {
            TestFixtures.createTestUser(email = "other@example.com", username = "takenname")

            val loginResponse = socialLogin()

            assertFailsWith<DuplicateUsernameException> {
                socialComplete(loginResponse.signupToken!!, "takenname")
            }

            // 실패 시 소셜 유저가 생성되면 안 된다
            val user = query {
                userRepository.findUserByEmail("social@example.com")
            }
            assertNull(user)
        }
    }

    @Test
    fun `가입 확정 실패 - 만료된 signupToken`() {
        runBlocking {
            val expiredToken = JwtConfig.makeSocialSignupToken(
                provider = "GOOGLE",
                providerUserId = "google-sub-1",
                email = "social@example.com",
                name = "홍길동",
                picture = null,
                validityMs = -1000L
            )

            assertFailsWith<TokenExpiredException> {
                socialComplete(expiredToken, "socialuser")
            }
        }
    }

    @Test
    fun `가입 확정 실패 - 위조된 signupToken`() {
        runBlocking {
            assertFailsWith<TokenExpiredException> {
                socialComplete("garbage.token.value", "socialuser")
            }
        }
    }

    @Test
    fun `가입 확정 실패 - 유효하지 않은 username`() {
        runBlocking {
            val loginResponse = socialLogin()

            assertFailsWith<InvalidUsernameException> {
                socialComplete(loginResponse.signupToken!!, "ab")
            }
        }
    }

    // ===== 소셜 전용 계정 가드 =====

    @Test
    fun `소셜 전용 계정 - 비밀번호 로그인 거부`() {
        runBlocking {
            val loginResponse = socialLogin()
            socialComplete(loginResponse.signupToken!!, "socialuser")

            assertFailsWith<SocialOnlyAccountException> {
                authService.login(
                    LoginRequest(email = "social@example.com", password = "anything123!"),
                    "TestAgent",
                    "127.0.0.1"
                )
            }
        }
    }

    @Test
    fun `소셜 전용 계정 - 비밀번호 재설정 요청 거부`() {
        runBlocking {
            val loginResponse = socialLogin()
            socialComplete(loginResponse.signupToken!!, "socialuser")

            assertFailsWith<SocialOnlyAccountException> {
                authService.requestPasswordReset("social@example.com")
            }
        }
    }

    // ===== 기타 =====

    @Test
    fun `추천 username - 중복 시 다른 후보 제안`() {
        runBlocking {
            TestFixtures.createTestUser(email = "other@example.com", username = "social")
            fakeVerifier.identity = defaultIdentity.copy(email = "social@gmail.com")

            val response = socialLogin()

            val suggested = response.suggestedUsername
            assertNotNull(suggested)
            assertTrue(suggested != "social", "이미 사용 중인 username은 추천하면 안 된다")
            assertTrue(suggested.length in Constants.User.MIN_USERNAME_LENGTH..Constants.User.MAX_USERNAME_LENGTH)

            val exists = query {
                userRepository.existsByUsername(suggested)
            }
            assertTrue(!exists)
        }
    }

    @Test
    fun `지원하지 않는 provider 거부`() {
        runBlocking {
            // FACEBOOK = enum에 없는 값. KAKAO/NAVER는 enum엔 있지만 테스트 verifier 목록에 없어 동일 예외.
            assertFailsWith<InvalidInputException> {
                authService.socialLogin(
                    SocialLoginRequest(provider = "FACEBOOK", token = "token"),
                    "TestAgent",
                    "127.0.0.1"
                )
            }
        }
    }
}
