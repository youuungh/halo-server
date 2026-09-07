package com.ninezero.features.user.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.user.domain.AuthService
import com.ninezero.features.user.domain.UserService
import com.ninezero.features.user.domain.UserSessionService
import com.ninezero.features.user.presentation.models.request.*
import com.ninezero.features.user.presentation.models.response.AuthResponse
import com.ninezero.features.user.presentation.models.response.DeviceListResponse
import com.ninezero.features.user.presentation.models.response.RefreshTokenResponse
import com.ninezero.features.user.presentation.models.response.RegisterResponse
import com.ninezero.features.user.presentation.models.response.SocialLoginResponse
import com.ninezero.features.user.presentation.models.response.UserResponse
import com.ninezero.features.user.presentation.models.response.VerifyResetCodeResponse
import com.ninezero.plugins.UserSession
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import org.koin.ktor.ext.inject

fun Route.authRoutes() {
    route(Constants.Endpoints.AUTH) {
        val authService by inject<AuthService>()
        val userService by inject<UserService>()
        val sessionService by inject<UserSessionService>()

        rateLimit(RateLimitName("auth")) {
            post("/register", {
                summary = "회원가입 인증 코드 발송 (공개)"
                tags("Auth")
                description = "1단계, 계정은 아직 생성 안 함"
                request {
                    body<RegisterRequest> {
                        description = "회원가입 정보"
                        required = true
                        example("default") {
                            value = RegisterRequest(
                                email = "user@example.com",
                                username = "rickastley",
                                password = "SecureP@ss123!"
                            )
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "인증 코드 발송 완료"
                        body<ApiResponse<RegisterResponse>> {
                            description = "토큰 없음 — needsVerification=true"
                        }
                    }
                    code(HttpStatusCode.Conflict) {
                        description = "이메일 또는 사용자명 중복"
                    }
                    code(HttpStatusCode.TooManyRequests) {
                        description = "재전송 쿨다운 또는 시간당 발송 상한 초과"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 입력 데이터"
                    }
                }
            }) {
                val request = call.receive<RegisterRequest>()
                val response = authService.register(request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response))
            }

            post("/login", {
                summary = "로그인 (공개)"
                tags("Auth")
                request {
                    body<LoginRequest> {
                        description = "로그인 정보"
                        required = true
                        example("default") {
                            value = LoginRequest(
                                email = "user@example.com",
                                password = "SecureP@ss123!",
                                fid = "example_fid_string"
                            )
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "로그인 성공"
                        body<ApiResponse<AuthResponse>> {
                            description = "JWT 토큰 포함 응답"
                        }
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "잘못된 이메일 또는 비밀번호"
                    }
                }
            }) {
                val request = call.receive<LoginRequest>()
                val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                val ipAddress = call.request.origin.remoteAddress

                val response = authService.login(request, userAgent, ipAddress)

                call.sessions.set(UserSession(response.accessToken))

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(response, Messages.Auth.LOGIN_SUCCESS)
                )
            }

            post("/verify-email-code", {
                summary = "회원가입 인증 코드 검증 (공개)"
                tags("Auth")
                description = "2단계, 계정 생성과 자동 로그인"
                request {
                    body<VerifyEmailCodeRequest> {
                        description = "이메일과 인증 코드"
                        required = true
                        example("default") {
                            value = VerifyEmailCodeRequest(
                                email = "user@example.com",
                                code = "A3F7K9"
                            )
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "계정 생성 완료"
                        body<ApiResponse<AuthResponse>> {
                            description = "JWT 토큰 포함 응답 (로그인과 동일)"
                        }
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "코드 불일치·시도 횟수 초과·진행 중인 가입 없음"
                    }
                    code(HttpStatusCode.Gone) {
                        description = "코드 만료"
                    }
                    code(HttpStatusCode.Conflict) {
                        description = "대기 중에 이메일 또는 사용자명이 선점됨"
                    }
                }
            }) {
                val request = call.receive<VerifyEmailCodeRequest>()
                val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                val ipAddress = call.request.origin.remoteAddress

                val response = authService.verifyEmailCode(request, userAgent, ipAddress)

                call.sessions.set(UserSession(response.accessToken))

                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse.success(response, Messages.Auth.LOGIN_SUCCESS)
                )
            }

            post("/social/login", {
                summary = "간편로그인 (공개)"
                tags("Auth")
                description = "기존·연동 계정이면 로그인 완료, 신규면 signupToken 반환"
                request {
                    body<SocialLoginRequest> {
                        description = "간편로그인 정보"
                        required = true
                        example("google") {
                            value = SocialLoginRequest(
                                provider = "GOOGLE",
                                token = "google-id-token"
                            )
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "로그인 완료(auth) 또는 username 온보딩 필요(signupToken)"
                        body<ApiResponse<SocialLoginResponse>>()
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "소셜 토큰 검증 실패"
                    }
                    code(HttpStatusCode.Conflict) {
                        description = "미인증 이메일 계정과 충돌"
                    }
                }
            }) {
                val request = call.receive<SocialLoginRequest>()
                val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                val ipAddress = call.request.origin.remoteAddress

                val response = authService.socialLogin(request, userAgent, ipAddress)

                response.auth?.let { call.sessions.set(UserSession(it.accessToken)) }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(response, Messages.Auth.LOGIN_SUCCESS)
                )
            }

            post("/social/complete", {
                summary = "간편로그인 가입 확정 (공개)"
                tags("Auth")
                description = "signupToken과 username으로 계정 생성 후 로그인"
                request {
                    body<SocialCompleteRequest> {
                        description = "가입 확정 정보"
                        required = true
                        example("google") {
                            value = SocialCompleteRequest(
                                signupToken = "signup-token-from-social-login",
                                username = "rickastley"
                            )
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "가입 완료, JWT 토큰 포함 응답"
                        body<ApiResponse<AuthResponse>>()
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "signupToken 만료/위조"
                    }
                    code(HttpStatusCode.Conflict) {
                        description = "이메일 또는 사용자명 중복"
                    }
                }
            }) {
                val request = call.receive<SocialCompleteRequest>()
                val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                val ipAddress = call.request.origin.remoteAddress

                val response = authService.socialComplete(request, userAgent, ipAddress)

                call.sessions.set(UserSession(response.accessToken))

                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse.success(response, Messages.Auth.LOGIN_SUCCESS)
                )
            }

            post("/resend-verification", {
                summary = "인증 코드 재발송 (공개)"
                tags("Auth")
                description = "대기 중인 가입 건 대상"
                request {
                    body<ResendVerificationRequest> {
                        description = "재발송할 이메일 주소"
                        required = true
                        example("default") {
                            value = ResendVerificationRequest(email = "user@example.com")
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "인증 코드 발송 완료"
                        body<ApiResponse<String>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "진행 중인 가입 요청이 없음(만료 포함)"
                    }
                    code(HttpStatusCode.TooManyRequests) {
                        description = "재전송 쿨다운 또는 시간당 발송 상한 초과"
                    }
                }
            }) {
                val request = call.receive<ResendVerificationRequest>()
                val response = authService.resendVerificationEmail(request.email)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/forgot-password", {
                summary = "비밀번호 재설정 요청 (공개)"
                tags("Auth")
                description = "인증 코드 발송"
                request {
                    body<ForgotPasswordRequest> {
                        description = "비밀번호 재설정할 이메일 주소"
                        required = true
                        example("default") {
                            value = ForgotPasswordRequest(email = "user@example.com")
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "비밀번호 재설정 인증 코드 발송 완료"
                        body<ApiResponse<String>>()
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "사용자를 찾을 수 없음"
                    }
                    code(HttpStatusCode.TooManyRequests) {
                        description = "재전송 쿨다운 또는 시간당 전송 상한 초과"
                    }
                }
            }) {
                val request = call.receive<ForgotPasswordRequest>()
                val response = authService.requestPasswordReset(request.email)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/verify-reset-code", {
                summary = "비밀번호 재설정 코드 검증 (공개)"
                tags("Auth")
                description = "재설정 토큰 발급"
                request {
                    body<VerifyResetCodeRequest> {
                        description = "이메일과 6자리 인증 코드"
                        required = true
                        example("default") {
                            value = VerifyResetCodeRequest(
                                email = "user@example.com",
                                code = "A3F9K2"
                            )
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "인증 코드 검증 성공 (재설정 토큰 발급)"
                        body<ApiResponse<VerifyResetCodeResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "유효하지 않은 인증 코드 또는 시도 횟수 초과"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "만료된 인증 코드"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "사용자를 찾을 수 없음"
                    }
                }
            }) {
                val request = call.receive<VerifyResetCodeRequest>()
                val resetToken = authService.verifyResetCode(request.email, request.code)
                call.respond(HttpStatusCode.OK, ApiResponse.success(VerifyResetCodeResponse(resetToken)))
            }

            post("/reset-password", {
                summary = "비밀번호 재설정 (공개)"
                tags("Auth")
                request {
                    body<ResetPasswordRequest> {
                        description = "재설정 토큰과 새 비밀번호"
                        required = true
                        example("default") {
                            value = ResetPasswordRequest(
                                token = "reset-token-from-email",
                                newPassword = "NewSecureP@ss123!"
                            )
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "비밀번호 재설정 완료"
                        body<ApiResponse<String>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "유효하지 않거나 만료된 토큰"
                    }
                }
            }) {
                val request = call.receive<ResetPasswordRequest>()
                val response = authService.resetPassword(request.token, request.newPassword)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        // 재발급은 로그인과 다른 rate limit 버킷
        rateLimit(RateLimitName("auth-refresh")) {
            post("/refresh", {
                summary = "토큰 재발급 (공개)"
                tags("Auth")
                request {
                    body<RefreshTokenRequest> {
                        description = "Refresh token"
                        required = true
                        example("default") {
                            value = RefreshTokenRequest(refreshToken = "uuid-refresh-token-string")
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "토큰 재발급 성공"
                        body<ApiResponse<RefreshTokenResponse>>()
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "유효하지 않거나 만료된 refresh token"
                    }
                }
            }) {
                val request = call.receive<RefreshTokenRequest>()
                val response = authService.refreshAccessToken(request.refreshToken)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        authenticate("jwt") {
            get("/me", {
                summary = "내 정보 조회"
                tags("Auth")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "사용자 정보 조회 성공"
                        body<ApiResponse<UserResponse>>()
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = authService.getCurrentUser(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/logout", {
                summary = "로그아웃"
                tags("Auth")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "로그아웃 성공"
                        body<ApiResponse<String>>()
                    }
                }
            }) {
                call.requireUserId() ?: return@post

                call.getSessionIdOrNull()?.let { sessionId ->
                    authService.logout(sessionId)
                }

                // 브라우저 쿠키 세션 정리
                call.sessions.clear<UserSession>()

                call.respond(HttpStatusCode.OK, ApiResponse.success(message = Messages.Auth.LOGOUT_SUCCESS))
            }

            post("/change-password", {
                summary = "비밀번호 변경"
                tags("Auth")
                description = "현재 비밀번호 확인 후 변경, 다른 기기 전부 로그아웃"
                request {
                    body<ChangePasswordRequest> {
                        description = "현재 비밀번호 + 새 비밀번호"
                        required = true
                        example("default") {
                            value = ChangePasswordRequest(
                                currentPassword = "OldP@ss123!",
                                newPassword = "NewP@ss456!"
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "비밀번호 변경 성공"
                        body<ApiResponse<String>>()
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "현재 비밀번호 불일치 또는 간편로그인 전용 계정"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post
                val request = call.receive<ChangePasswordRequest>()

                val response = authService.changePassword(userId, call.getSessionIdOrNull(), request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            delete("/me", {
                summary = "계정 탈퇴"
                tags("Auth")
                description = "즉시 익명화, 크리에이터는 불가"
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "탈퇴 완료"
                        body<ApiResponse<String>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터/관리자 계정"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val response = authService.deleteAccount(userId)

                call.sessions.clear<UserSession>()

                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/fcm-token", {
                summary = "FCM 토큰 등록"
                tags("Auth")
                request {
                    body<FcmRegistrationRequest> {
                        description = "FCM 토큰"
                        required = true
                        example("default") {
                            value = FcmRegistrationRequest(fid = "example_fid_string")
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "FCM 토큰 등록 성공"
                        body<ApiResponse<String>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 요청"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<FcmRegistrationRequest>()
                // deviceId 있어야 기기별 등록 가능
                request.deviceId?.let { deviceId ->
                    userService.registerDeviceToken(userId, deviceId, request.fid)
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(message = Messages.Auth.FCM_TOKEN_REGISTERED))
            }

            delete("/fcm-token", {
                summary = "FCM 토큰 삭제"
                tags("Auth")
                description = "로그아웃 시"
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "FCM 토큰 삭제 성공"
                        body<ApiResponse<String>>()
                    }
                }
            }) {
                call.requireUserId() ?: return@delete

                // 이 기기 토큰만 삭제
                call.request.queryParameters["deviceId"]?.let { deviceId ->
                    userService.deleteDeviceToken(deviceId)
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(message = Messages.Auth.FCM_TOKEN_DELETED))
            }

            get("/devices", {
                summary = "활성 기기 목록 조회"
                tags("Auth")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "디바이스 목록 조회 성공"
                        body<ApiResponse<DeviceListResponse>>()
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val currentSessionId = call.getSessionIdOrNull() ?: ""

                val response = sessionService.getUserDevices(userId, currentSessionId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            delete("/devices/{sessionId}", {
                summary = "기기 로그아웃"
                tags("Auth")
                authResponse()
                request {
                    pathParameter<String>("sessionId") {
                        description = "세션 ID"
                        required = true
                    }
                }
                response {
                    code(HttpStatusCode.OK) {
                        description = "디바이스 로그아웃 성공"
                        body<ApiResponse<String>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "현재 디바이스는 로그아웃 불가"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val sessionIdToDelete = call.parameters["sessionId"]
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>(Errors.User.SESSION_ID_REQUIRED)
                    )

                val currentSessionId = call.getSessionIdOrNull()

                // 현재 세션은 삭제 불가
                if (sessionIdToDelete == currentSessionId) {
                    return@delete call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse.error<Unit>(Errors.User.DEVICE_LOGOUT_CURRENT)
                    )
                }

                sessionService.revokeDevice(userId, sessionIdToDelete)
                authService.logout(sessionIdToDelete)
                call.respond(HttpStatusCode.OK, ApiResponse.success(message = Messages.Auth.DEVICE_LOGOUT_SUCCESS))
            }
        }
    }
}
