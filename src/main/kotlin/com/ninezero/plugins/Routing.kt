package com.ninezero.plugins

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.exception.AppException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.validateRequest
import com.ninezero.features.admin.presentation.monitoringRoutes
import com.ninezero.core.websocket.realtimeWebSocket
import com.ninezero.features.chat.presentation.chatRoutes
import com.ninezero.features.chat.presentation.messageRoutes
import com.ninezero.features.chat.presentation.models.request.SendMessageRequest
import com.ninezero.features.commerce.presentation.*
import com.ninezero.features.commerce.presentation.models.request.*
import com.ninezero.features.coupon.presentation.couponRoutes
import com.ninezero.features.coupon.presentation.models.request.ClaimCouponRequest
import com.ninezero.features.coupon.presentation.models.request.CouponRequest
import com.ninezero.features.coupon.presentation.models.request.UpdateCouponRequest
import com.ninezero.features.notification.presentation.notificationRoutes
import com.ninezero.features.point.presentation.pointRoutes
import com.ninezero.features.banner.presentation.bannerRoutes
import com.ninezero.features.search.presentation.searchRoutes
import com.ninezero.features.social.presentation.*
import com.ninezero.features.share.presentation.shareRoutes
import com.ninezero.features.tag.presentation.tagRoutes
import com.ninezero.features.subscription.presentation.models.request.SubscribeRequest
import com.ninezero.features.subscription.presentation.models.request.SubscriptionPlanRequest
import com.ninezero.features.subscription.presentation.models.request.UpdatePlanRequest
import com.ninezero.features.subscription.presentation.subscriptionPlanRoutes
import com.ninezero.features.subscription.presentation.subscriptionRoutes
import com.ninezero.features.user.presentation.*
import com.ninezero.features.user.presentation.models.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    configureRequestValidation()
    configureStatusPages()
    registerApiRoutes()
}

private fun Application.configureRequestValidation() {
    install(RequestValidation) {
        // User 검증

        validate<RegisterRequest> {
            validateRequest {
                ValidationUtils.validateEmail(it.email)
                ValidationUtils.validatePassword(it.password)
                ValidationUtils.validateUsername(it.username)
            }
        }

        validate<ResendVerificationRequest> {
            validateRequest {
                ValidationUtils.validateEmail(it.email)
            }
        }

        validate<ForgotPasswordRequest> {
            validateRequest {
                ValidationUtils.validateEmail(it.email)
            }
        }

        validate<VerifyResetCodeRequest> {
            validateRequest {
                ValidationUtils.validateEmail(it.email)
                if (it.code.length != Constants.User.VERIFICATION_CODE_LENGTH) {
                    throw InvalidInputException(Errors.User.RESET_CODE_INVALID)
                }
            }
        }

        validate<ResetPasswordRequest> {
            validateRequest {
                if (it.token.isBlank()) throw InvalidInputException("토큰은 필수입니다.")
                ValidationUtils.validatePassword(it.newPassword, "새 비밀번호")
            }
        }

        validate<LoginRequest> { request ->
            when {
                request.email.isBlank() -> ValidationResult.Invalid("이메일은 필수입니다.")
                request.password.isBlank() -> ValidationResult.Invalid("비밀번호는 필수입니다.")
                else -> ValidationResult.Valid
            }
        }

        validate<UpdateProfileRequest> {
            validateRequest {
                ValidationUtils.validateDisplayName(it.displayName)
                ValidationUtils.validateBio(it.bio)
                ValidationUtils.validateLocation(it.location)
                ValidationUtils.validateWebsite(it.website)
            }
        }

        validate<CreatorApplicationRequest> {
            validateRequest {
                ValidationUtils.validateCreatorApplicationReason(it.reason)
            }
        }

        validate<RejectApplicationRequest> {
            validateRequest {
                ValidationUtils.validateRejectionReason(it.rejectionReason)
            }
        }

        // Address 검증

        validate<CreateAddressRequest> {
            validateRequest {
                ValidationUtils.validateRecipientName(it.recipientName)
                ValidationUtils.validateRecipientPhone(it.recipientPhone)
                ValidationUtils.validateZipCode(it.zipCode)
                ValidationUtils.validateAddress(it.address)
                ValidationUtils.validateAddressDetail(it.addressDetail)
                ValidationUtils.validateAddressMemo(it.memo)
            }
        }

        validate<UpdateAddressRequest> {
            validateRequest {
                ValidationUtils.validateRecipientName(it.recipientName)
                ValidationUtils.validateRecipientPhone(it.recipientPhone)
                ValidationUtils.validateZipCode(it.zipCode)
                ValidationUtils.validateAddress(it.address)
                ValidationUtils.validateAddressDetail(it.addressDetail)
                ValidationUtils.validateAddressMemo(it.memo)
            }
        }

        // Commerce 검증

        validate<CartRequest> {
            validateRequest {
                ValidationUtils.validateCartQuantity(it.quantity)
            }
        }

        validate<OrderRequest> {
            validateRequest {
                ValidationUtils.validateShippingAddress(it.shippingAddress)
                ValidationUtils.validateShippingName(it.shippingName)
                ValidationUtils.validateShippingPhone(it.shippingPhone)
            }
        }

        // Chat 검증

        validate<SendMessageRequest> {
            validateRequest {
                ValidationUtils.validateChatMessage(it.content, it.mediaAttachments)
            }
        }

        // Subscription 검증

        validate<SubscriptionPlanRequest> {
            validateRequest {
                ValidationUtils.validatePlanName(it.name)
                ValidationUtils.validatePlanDescription(it.description)
                ValidationUtils.validatePrice(it.price, "플랜 가격")
                ValidationUtils.validatePlanBenefits(it.benefits)
            }
        }

        validate<UpdatePlanRequest> {
            validateRequest {
                it.name?.let { name -> ValidationUtils.validatePlanName(name) }
                it.description?.let { desc -> ValidationUtils.validatePlanDescription(desc) }
                it.benefits?.let { benefits -> ValidationUtils.validatePlanBenefits(benefits) }
            }
        }

        validate<SubscribeRequest> {
            validateRequest {
                ValidationUtils.validatePlanId(it.planId)
            }
        }

        // Coupon 검증

        validate<CouponRequest> {
            validateRequest {
                ValidationUtils.validateManualCouponCode(it.code)
                ValidationUtils.validateCouponName(it.name)
                ValidationUtils.validateCouponDescription(it.description)

                when (it.type) {
                    CouponType.PERCENTAGE -> {
                        val rate = it.discountValue.toIntOrNull()
                            ?: throw InvalidInputException("할인율은 숫자여야 합니다.")
                        ValidationUtils.validateCouponDiscountRate(rate)
                    }

                    CouponType.FIXED_AMOUNT -> {
                        ValidationUtils.validateCouponDiscountAmount(it.discountValue)
                    }

                    CouponType.FREE_SHIPPING -> {
                        // 검증 불필요
                    }
                }

                ValidationUtils.validateCouponMinOrderAmount(it.minOrderAmount)
                ValidationUtils.validateCouponQuantity(it.totalQuantity)
                ValidationUtils.validateCouponUseCount(it.maxUseCount)
                ValidationUtils.validateCouponDates(it.startDate, it.endDate)
            }
        }

        validate<UpdateCouponRequest> {
            validateRequest {
                it.name?.let { name -> ValidationUtils.validateCouponName(name) }
                it.description?.let { desc -> ValidationUtils.validateCouponDescription(desc) }
                it.minOrderAmount?.let { amount -> ValidationUtils.validateCouponMinOrderAmount(amount) }
                it.totalQuantity?.let { qty -> ValidationUtils.validateCouponQuantity(qty) }
                it.maxUseCount?.let { count -> ValidationUtils.validateCouponUseCount(count) }
            }
        }

        validate<ClaimCouponRequest> {
            validateRequest {
                ValidationUtils.validateCouponCode(it.code)
            }
        }
    }
}

private fun Application.configureStatusPages() {
    install(StatusPages) {
        // AppException 자동 처리

        exception<AppException> { call, cause ->
            call.respond(
                cause.statusCode,
                ApiResponse.error<Unit>(cause.message ?: Errors.Common.INTERNAL_ERROR)
            )
        }

        // Ktor 내장 Exception 처리

        exception<MissingRequestParameterException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Unit>("필수 파라미터가 누락되었습니다: ${cause.parameterName}")
            )
        }

        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Unit>(cause.message ?: Errors.Common.INVALID_REQUEST)
            )
        }

        // RequestBodyLimit 초과는 413
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ApiResponse.error<Unit>("업로드 용량이 허용 한도를 초과했습니다")
            )
        }

        // Kotlin 기본 Exception 처리

        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Unit>(cause.reasons.joinToString("\n"))
            )
        }

        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Unit>(cause.message ?: Errors.Common.INVALID_REQUEST)
            )
        }

        exception<IllegalStateException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Unit>(cause.message ?: Errors.Common.INVALID_STATE)
            )
        }

        exception<NumberFormatException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Unit>(Errors.Common.INVALID_NUMBER_FORMAT)
            )
        }

        exception<NullPointerException> { call, cause ->
            call.application.log.error("NullPointerException 발생", cause)

            val environment = DotenvConfig.getOrDefault("ENVIRONMENT", "dev")
            val isDev = environment != "prod"

            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse.error<Unit>(if (isDev) {
                    "Null 참조 오류: ${cause.message}"
                } else {
                    Errors.Common.INTERNAL_ERROR
                })
            )
        }

        // 모든 Exception

        exception<Throwable> { call, cause ->
            call.application.log.error("처리되지 않은 예외 발생: ${cause.javaClass.simpleName}", cause)

            val environment = DotenvConfig.getOrDefault("ENVIRONMENT", "dev")
            val isDev = environment != "prod"

            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse.error<Unit>(if (isDev) {
                    "서버 오류: ${cause.message}"
                } else {
                    Errors.Common.INTERNAL_ERROR
                })
            )
        }

        // HTTP Status 처리

        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiResponse.error<Unit>(Errors.Common.AUTH_REQUIRED)
            )
        }

        status(HttpStatusCode.Forbidden) { call, _ ->
            call.respond(
                HttpStatusCode.Forbidden,
                ApiResponse.error<Unit>(Errors.Common.PERMISSION_DENIED)
            )
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse.error<Unit>(Errors.Common.NOT_FOUND)
            )
        }

        status(HttpStatusCode.BadRequest) { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Unit>(Errors.Common.INVALID_REQUEST)
            )
        }
    }
}

private fun Application.registerApiRoutes() {
    routing {
        staticResources("/static", "static")

        // User 관련 라우트
        authRoutes()
        profileRoutes()
        userRoutes()
        creatorApplicationRoutes()
        blockedUserRoutes()

        // Social 라우트
        postRoutes()
        commentRoutes()
        likeRoutes()
        bookmarkRoutes()
        hiddenPostRoutes()
        followRoutes()
        feedRoutes()
        tagRoutes()
        communityRoutes()
        shareRoutes()
        reportRoutes()
        adminReportRoutes()

        // Address 라우트
        addressRoutes()

        // Commerce 라우트
        productRoutes()
        cartRoutes()
        orderRoutes()
        reviewRoutes()
        wishlistRoutes()
        paymentRoutes()
        billingRoutes()
        deliveryWebhookRoutes()

        // Notification 라우트
        notificationRoutes()

        // Chat 라우트
        chatRoutes()
        messageRoutes()

        // 실시간 소켓
        realtimeWebSocket()

        // Subscription 라우트
        subscriptionPlanRoutes()
        subscriptionRoutes()

        // Search 라우트
        searchRoutes()

        // Banner 라우트
        bannerRoutes()

        // Point 라우트
        pointRoutes()

        // Coupon 라우트
        couponRoutes()

        // Monitoring 라우트
        monitoringRoutes()
    }
}
