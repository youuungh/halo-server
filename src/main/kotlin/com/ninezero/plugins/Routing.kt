package com.ninezero.plugins

import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.exception.AppException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.websocket.realtimeWebSocket
import com.ninezero.features.banner.presentation.bannerRoutes
import com.ninezero.features.chat.presentation.chatRoutes
import com.ninezero.features.chat.presentation.messageRoutes
import com.ninezero.features.commerce.presentation.*
import com.ninezero.features.coupon.presentation.couponRoutes
import com.ninezero.features.coupon.presentation.validateCouponRequests
import com.ninezero.features.monitoring.presentation.monitoringRoutes
import com.ninezero.features.notification.presentation.notificationRoutes
import com.ninezero.features.point.presentation.pointRoutes
import com.ninezero.features.search.presentation.searchRoutes
import com.ninezero.features.share.presentation.shareRoutes
import com.ninezero.features.social.presentation.*
import com.ninezero.features.subscription.presentation.subscriptionPlanRoutes
import com.ninezero.features.subscription.presentation.subscriptionRoutes
import com.ninezero.features.subscription.presentation.validateSubscriptionRequests
import com.ninezero.features.tag.presentation.tagRoutes
import com.ninezero.features.upload.presentation.mediaDraftRoutes
import com.ninezero.features.user.presentation.*
import com.ninezero.features.user.presentation.models.request.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException

/** 유니크 위반 SQLSTATE */
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

fun Application.configureRouting() {
    configureRequestValidation()
    configureStatusPages()
    registerApiRoutes()
}

private fun Application.configureRequestValidation() {
    install(RequestValidation) {
        validateUserRequests()
        validateSubscriptionRequests()
        validateCouponRequests()
    }
}

internal fun Application.configureStatusPages() {
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

        // 동시 요청이 유니크 제약에 걸린 경우
        exception<ExposedSQLException> { call, cause ->
            if (cause.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                call.respond(HttpStatusCode.Conflict, ApiResponse.error<Unit>(Errors.Common.ALREADY_EXISTS))
            } else {
                call.application.log.error("데이터베이스 오류", cause)
                call.respond(HttpStatusCode.InternalServerError, ApiResponse.error<Unit>(Errors.Common.INTERNAL_ERROR))
            }
        }

        // 모든 Exception

        exception<Throwable> { call, cause ->
            call.application.log.error("처리되지 않은 예외 발생: {}", cause.javaClass.simpleName, cause)

            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse.error<Unit>(if (DotenvConfig.isProd) {
                    Errors.Common.INTERNAL_ERROR
                } else {
                    "서버 오류: ${cause.message}"
                })
            )
        }

        // HTTP Status 처리(본문 없는 응답만 공통 문구)

        status(HttpStatusCode.Unauthorized) { status ->
            if (content is OutgoingContent.NoContent) {
                call.respond(status, ApiResponse.error<Unit>(Errors.Common.AUTH_REQUIRED))
            }
        }

        status(HttpStatusCode.Forbidden) { status ->
            if (content is OutgoingContent.NoContent) {
                call.respond(status, ApiResponse.error<Unit>(Errors.Common.PERMISSION_DENIED))
            }
        }

        status(HttpStatusCode.NotFound) { status ->
            if (content is OutgoingContent.NoContent) {
                call.respond(status, ApiResponse.error<Unit>(Errors.Common.NOT_FOUND))
            }
        }

        status(HttpStatusCode.BadRequest) { status ->
            if (content is OutgoingContent.NoContent) {
                call.respond(status, ApiResponse.error<Unit>(Errors.Common.INVALID_REQUEST))
            }
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

        // Upload 라우트
        mediaDraftRoutes()

        // Monitoring 라우트
        monitoringRoutes()
    }
}
