package com.ninezero.features.commerce.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.apiResponse
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.core.common.util.requireUserId
import com.ninezero.core.common.util.authResponse
import com.ninezero.features.commerce.domain.PaymentService
import com.ninezero.features.commerce.presentation.models.request.TossConfirmRequest
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.paymentRoutes() {
    route(Constants.Endpoints.PAYMENT) {
        val paymentService by inject<PaymentService>()

        authenticate("jwt") {
            rateLimit(RateLimitName("payment")) {
                post({
                    summary = "Mock 결제"
                    tags("Payments")
                    request {
                        queryParameter<Int>("orderId") {
                            description = "주문 ID"
                            required = true
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.BadRequest) {
                            description = "결제 불가능한 주문 상태"
                        }
                        code(HttpStatusCode.NotFound) {
                            description = "주문 없음"
                        }
                        code(HttpStatusCode.Forbidden) {
                            description = "결제 권한 없음"
                        }
                        code(HttpStatusCode.Unauthorized) {
                            description = "인증 필요"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@post

                    val orderId = call.getRequiredIntParam("orderId", Errors.Commerce.Order.INVALID_ORDER_ID)
                        ?: return@post

                    val response = paymentService.processPayment(orderId, userId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                delete("/{orderId}", {
                    summary = "결제 환불"
                    tags("Payments")
                    request {
                        pathParameter<Int>("orderId") {
                            description = "주문 ID"
                            required = true
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.BadRequest) {
                            description = "환불 불가능한 상태 (이미 환불됨 또는 환불 가능 기간 초과)"
                        }
                        code(HttpStatusCode.NotFound) {
                            description = "주문 또는 결제 없음"
                        }
                        code(HttpStatusCode.Forbidden) {
                            description = "환불 권한 없음"
                        }
                        code(HttpStatusCode.Unauthorized) {
                            description = "인증 필요"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@delete

                    val orderId = call.getRequiredIntParam("orderId", Errors.Commerce.Order.INVALID_ORDER_ID)
                        ?: return@delete

                    val response = paymentService.refundPayment(orderId, userId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                get("/{orderId}", {
                    summary = "결제 상태 조회"
                    tags("Payments")
                    request {
                        pathParameter<Int>("orderId") {
                            description = "주문 ID"
                            required = true
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.NotFound) {
                            description = "주문 또는 결제 없음"
                        }
                        code(HttpStatusCode.Forbidden) {
                            description = "결제 조회 권한 없음"
                        }
                        code(HttpStatusCode.Unauthorized) {
                            description = "인증 필요"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@get

                    val orderId = call.getRequiredIntParam("orderId", Errors.Commerce.Order.INVALID_ORDER_ID)
                        ?: return@get

                    val response = paymentService.getPaymentStatus(orderId, userId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                post("/toss/prepare", {
                    summary = "토스 결제 준비"
                    tags("Payments")
                    request {
                        queryParameter<Int>("orderId") {
                            description = "주문 ID"
                            required = true
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.BadRequest) {
                            description = "결제 준비 불가능한 주문 상태"
                        }
                        code(HttpStatusCode.NotFound) {
                            description = "주문 없음"
                        }
                        code(HttpStatusCode.Forbidden) {
                            description = "결제 준비 권한 없음"
                        }
                        code(HttpStatusCode.Unauthorized) {
                            description = "인증 필요"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@post

                    val orderId = call.getRequiredIntParam("orderId", Errors.Commerce.Order.INVALID_ORDER_ID)
                        ?: return@post

                    val response = paymentService.prepareTossPayment(orderId, userId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Commerce.TOSS_PAYMENT_PREPARED))
                }

                post("/toss/confirm", {
                    summary = "토스 결제 승인"
                    tags("Payments")
                    request {
                        body<TossConfirmRequest> {
                            description = "Toss 결제 승인 데이터"
                            required = true
                            example("card") {
                                value = TossConfirmRequest(
                                    paymentKey = "tgen_20250101ABCD1234",
                                    orderId = 1,
                                    amount = 50000
                                )
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.UnprocessableEntity) {
                            description = "결제 승인 실패 또는 금액 불일치"
                        }
                        code(HttpStatusCode.NotFound) {
                            description = "주문 또는 결제 없음"
                        }
                        code(HttpStatusCode.Forbidden) {
                            description = "결제 권한 없음"
                        }
                        code(HttpStatusCode.Unauthorized) {
                            description = "인증 필요"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@post

                    val request = call.receive<TossConfirmRequest>()
                    val response = paymentService.confirmTossPayment(
                        orderId = request.orderId,
                        paymentKey = request.paymentKey,
                        amount = request.amount,
                        userId = userId
                    )
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }
            }
        }
    }
}
