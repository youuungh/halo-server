package com.ninezero.features.commerce.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.apiResponse
import com.ninezero.features.commerce.domain.OrderService
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject

fun Route.deliveryWebhookRoutes() {
    route(Constants.Endpoints.WEBHOOKS) {
        val orderService by inject<OrderService>()

        post("/delivery", {
            summary = "배송 상태 웹훅 (외부)"
            tags("Webhooks")
            apiResponse()
            response {
                code(HttpStatusCode.OK) {
                    description = "웹훅 처리 성공"
                }
                code(HttpStatusCode.Forbidden) {
                    description = "서명 검증 실패"
                }
                code(HttpStatusCode.BadRequest) {
                    description = "유효하지 않은 웹훅 payload"
                }
            }
        }) {
            val rawBody = call.receiveText()
            val signature = call.request.header("X-Webhook-Signature")
            val timestamp = call.request.header("X-Webhook-Timestamp")

            val result = orderService.handleDeliveryWebhook(
                rawBody = rawBody,
                signatureHeader = signature,
                timestampHeader = timestamp
            )

            call.respond(HttpStatusCode.OK, ApiResponse.success(result))
        }
    }
}
