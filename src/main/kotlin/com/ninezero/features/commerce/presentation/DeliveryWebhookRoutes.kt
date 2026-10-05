package com.ninezero.features.commerce.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.apiResponse
import com.ninezero.features.commerce.domain.OrderShippingService
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
        val orderShippingService by inject<OrderShippingService>()

        post("/delivery", {
            summary = "배송 상태 웹훅 (공개)"
            tags("Webhooks")
            apiResponse()
            response {
                code(HttpStatusCode.OK) {
                    description = "processed 또는 ignored, 서명 실패·모르는 형식도 200"
                }
            }
        }) {
            val rawBody = call.receiveText()
            val signature = call.request.header("X-Webhook-Signature")
            val timestamp = call.request.header("X-Webhook-Timestamp")

            val result = orderShippingService.handleDeliveryWebhook(
                rawBody = rawBody,
                signatureHeader = signature,
                timestampHeader = timestamp
            )

            call.respond(HttpStatusCode.OK, ApiResponse.success(result))
        }
    }
}
