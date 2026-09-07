package com.ninezero.features.commerce.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.apiResponse
import com.ninezero.core.common.util.requireUserId
import com.ninezero.core.common.util.authResponse
import com.ninezero.features.commerce.domain.BillingService
import com.ninezero.features.commerce.domain.PaymentService
import com.ninezero.features.commerce.presentation.models.request.BillingByCardRequest
import com.ninezero.features.commerce.presentation.models.request.BillingIssueRequest
import com.ninezero.features.commerce.presentation.models.request.BillingPayRequest
import com.ninezero.features.commerce.presentation.models.response.CustomerKeyResponse
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

fun Route.billingRoutes() {
    route(Constants.Endpoints.BILLING) {
        val billingService by inject<BillingService>()
        val paymentService by inject<PaymentService>()

        authenticate("jwt") {
            rateLimit(RateLimitName("payment")) {
                get("/customer-key", {
                    summary = "customerKey 조회/발급"
                    tags("Billing")
                    authResponse()
                }) {
                    val userId = call.requireUserId() ?: return@get
                    val customerKey = billingService.getOrCreateCustomerKey(userId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(CustomerKeyResponse(customerKey)))
                }

                post("/issue", {
                    summary = "빌링키 발급"
                    tags("Billing")
                    description = "authKey를 billingKey로 교환"
                    authResponse()
                }) {
                    val userId = call.requireUserId() ?: return@post
                    val request = call.receive<BillingIssueRequest>()
                    val response = billingService.issueBillingKey(userId, request.authKey)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                post("/issue-by-card", {
                    summary = "빌링키 발급 (테스트)"
                    tags("Billing")
                    description = "카드정보 직접 입력"
                    authResponse()
                }) {
                    val userId = call.requireUserId() ?: return@post
                    val request = call.receive<BillingByCardRequest>()
                    val response = billingService.issueBillingKeyByCard(
                        userId = userId,
                        cardNumber = request.cardNumber,
                        expirationYear = request.expirationYear,
                        expirationMonth = request.expirationMonth,
                        identityNumber = request.identityNumber,
                        cardPassword = request.cardPassword
                    )
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                get("/my", {
                    summary = "등록 빌링 카드 조회"
                    tags("Billing")
                    description = "없으면 registered=false"
                    authResponse()
                }) {
                    val userId = call.requireUserId() ?: return@get
                    call.respond(HttpStatusCode.OK, ApiResponse.success(billingService.getMyBillingKey(userId)))
                }

                post("/pay", {
                    summary = "빌링키로 주문 결제"
                    tags("Billing")
                    authResponse()
                }) {
                    val userId = call.requireUserId() ?: return@post
                    val request = call.receive<BillingPayRequest>()
                    val message = paymentService.payByBilling(request.orderId, userId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(message))
                }

                delete("/my", {
                    summary = "등록 빌링 카드 삭제"
                    tags("Billing")
                    authResponse()
                }) {
                    val userId = call.requireUserId() ?: return@delete
                    billingService.deleteBillingKey(userId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success("결제수단이 삭제되었습니다."))
                }
            }
        }
    }
}
