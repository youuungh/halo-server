package com.ninezero.features.commerce.presentation

import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.commerce.domain.OrderService
import com.ninezero.features.commerce.presentation.models.request.OrderRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateOrderStatusRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateShippingRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateShippingStatusRequest
import com.ninezero.features.commerce.presentation.models.response.OrderStatisticsResponse
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.orderRoutes() {
    route(Constants.Endpoints.ORDERS) {
        val orderService by inject<OrderService>()

        authenticate("jwt") {
            post({
                summary = "주문 생성"
                tags("Orders")
                request {
                    body<OrderRequest> {
                        description = "주문 정보 (배송지, 결제 수단 포함)"
                        required = true
                        example("default") {
                            value = OrderRequest(
                                shippingAddress = "서울시 강남구 테헤란로 123",
                                shippingPhone = "010-1234-5678",
                                shippingName = "홍길동",
                                paymentProvider = PaymentProvider.TOSS_PAYMENTS,
                                memo = "부재 시 경비실에 맡겨주세요",
                                pointsToUse = "5000",
                                couponCodes = listOf("WELCOME10")
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "주문 생성 성공"
                        body<ApiResponse<Any>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 요청 데이터 또는 빈 장바구니"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<OrderRequest>()
                val response = orderService.createOrderFromCart(userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Commerce.ORDER_CREATED))
            }

            get("/{id}", {
                summary = "주문 상세 조회"
                tags("Orders")
                request {
                    pathParameter<Int>("id") {
                        description = "주문 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "주문 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "주문 조회 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val orderId = call.getRequiredIntParam("id", Errors.Commerce.Order.INVALID_ORDER_ID)
                    ?: return@get

                val response = orderService.getOrderById(orderId, userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my", {
                summary = "내 주문 목록 조회"
                tags("Orders")
                request {
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = orderService.getMyOrders(userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            // 결제 라우트와 동일 rate limit
            rateLimit(RateLimitName("payment")) {
                delete("/{id}", {
                    summary = "주문 취소"
                    tags("Orders")
                    request {
                        pathParameter<Int>("id") {
                            description = "주문 ID"
                            required = true
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.BadRequest) {
                            description = "취소 불가능한 상태 (이미 배송 중이거나 배송 완료)"
                        }
                        code(HttpStatusCode.NotFound) {
                            description = "주문 없음"
                        }
                        code(HttpStatusCode.Forbidden) {
                            description = "주문 취소 권한 없음"
                        }
                        code(HttpStatusCode.Unauthorized) {
                            description = "인증 필요"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@delete

                    val orderId = call.getRequiredIntParam("id", Errors.Commerce.Order.INVALID_ORDER_ID)
                        ?: return@delete

                    val cancelReason = call.request.queryParameters["reason"]?.takeIf { it.isNotBlank() } ?: "구매자 요청"
                    val response = orderService.cancelOrder(orderId, userId, cancelReason)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Commerce.ORDER_CANCELLED))
                }

                delete("/{id}/groups/{creatorId}", {
                    summary = "크리에이터 그룹 취소"
                    tags("Orders")
                    description = "주문 내 특정 크리에이터 상품만 취소"
                    request {
                        pathParameter<Int>("id") {
                            description = "주문 ID"
                            required = true
                        }
                        pathParameter<Int>("creatorId") {
                            description = "취소할 크리에이터 ID"
                            required = true
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.BadRequest) {
                            description = "취소 불가능한 상태"
                        }
                        code(HttpStatusCode.NotFound) {
                            description = "주문 또는 크리에이터 그룹 없음"
                        }
                        code(HttpStatusCode.Forbidden) {
                            description = "주문 취소 권한 없음"
                        }
                        code(HttpStatusCode.Unauthorized) {
                            description = "인증 필요"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@delete

                    val orderId = call.getRequiredIntParam("id", Errors.Commerce.Order.INVALID_ORDER_ID)
                        ?: return@delete
                    val creatorId = call.getRequiredIntParam("creatorId", Errors.Commerce.Order.INVALID_ORDER_ID)
                        ?: return@delete

                    val cancelReason = call.request.queryParameters["reason"]?.takeIf { it.isNotBlank() } ?: "구매자 요청"
                    val response = orderService.cancelOrderGroup(orderId, userId, creatorId, cancelReason)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Commerce.ORDER_CANCELLED))
                }
            }

            get("/creator", {
                summary = "판매자 주문 목록 조회 (Creator)"
                tags("Orders")
                request {
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<String>("filter") {
                        description = "상태 필터 (ALL/PENDING/SHIPPED/DELIVERED/CANCELLED, 기본값: ALL)"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터 권한 필요"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val creatorId = call.requireUserId() ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val filter = call.request.queryParameters["filter"]

                val response = orderService.getCreatorOrders(creatorId, page, limit, filter)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/creator/sales/summary", {
                summary = "판매자 매출 요약 (Creator)"
                tags("Orders")
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터 권한 필요"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val creatorId = call.requireUserId() ?: return@get

                val response = orderService.getCreatorSalesSummary(creatorId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/creator/sales/stats", {
                summary = "판매자 월별 매출 통계 (Creator)"
                tags("Orders")
                request {
                    queryParameter<String>("month") {
                        description = "조회 월 (YYYY-MM, 기본값: 이번 달)"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터 권한 필요"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val creatorId = call.requireUserId() ?: return@get

                val month = call.request.queryParameters["month"]
                val response = orderService.getCreatorMonthlySales(creatorId, month)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            patch("/{id}/status", {
                summary = "주문 상태 변경 (Creator)"
                tags("Orders")
                request {
                    pathParameter<Int>("id") {
                        description = "주문 ID"
                        required = true
                    }
                    body<UpdateOrderStatusRequest> {
                        description = "변경할 주문 상태"
                        required = true
                        example("pending") {
                            value = UpdateOrderStatusRequest(status = OrderStatus.PENDING)
                        }
                        example("confirmed") {
                            value = UpdateOrderStatusRequest(status = OrderStatus.CONFIRMED)
                        }
                        example("delivered") {
                            value = UpdateOrderStatusRequest(status = OrderStatus.DELIVERED)
                        }
                        example("cancelled") {
                            value = UpdateOrderStatusRequest(status = OrderStatus.CANCELLED)
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 상태 전환"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "주문 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "주문 상태 변경 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val creatorId = call.requireUserId() ?: return@patch

                val orderId = call.getRequiredIntParam("id", Errors.Commerce.Order.INVALID_ORDER_ID)
                    ?: return@patch

                val request = call.receive<UpdateOrderStatusRequest>()
                val response = orderService.updateOrderStatus(orderId, creatorId, request)
                val message = if (request.status == OrderStatus.CANCELLED) {
                    Messages.Commerce.ORDER_CANCELLED
                } else {
                    Messages.Commerce.ORDER_STATUS_UPDATED
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, message))
            }

            put("/{id}/shipping", {
                summary = "배송 정보 등록 (Creator)"
                tags("Orders")
                request {
                    pathParameter<Int>("id") {
                        description = "주문 ID"
                        required = true
                    }
                    body<UpdateShippingRequest> {
                        description = "운송장 번호 및 택배사 정보"
                        required = true
                        example("default") {
                            value = UpdateShippingRequest(
                                trackingNumber = "1234567890123",
                                carrier = "CJ대한통운"
                            )
                        }
                        example("hanjin") {
                            value = UpdateShippingRequest(
                                trackingNumber = "9876543210987",
                                carrier = "한진택배"
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 운송장 번호 또는 택배사"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "주문 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "배송 정보 등록 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val orderId = call.getRequiredIntParam("id", Errors.Commerce.Order.INVALID_ORDER_ID)
                    ?: return@put

                val request = call.receive<UpdateShippingRequest>()
                val response = orderService.updateShippingInfo(userId, orderId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Commerce.SHIPPING_INFO_REGISTERED))
            }

            get("/{id}/shipping", {
                summary = "배송 상태 조회"
                tags("Orders")
                request {
                    pathParameter<Int>("id") {
                        description = "주문 ID"
                        required = true
                    }
                    queryParameter<Int>("creatorId") {
                        description = "크리에이터별 배송 그룹 ID"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "주문 또는 배송 정보 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "배송 정보 조회 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val orderId = call.getRequiredIntParam("id", Errors.Commerce.Order.INVALID_ORDER_ID)
                    ?: return@get
                val creatorId = call.request.queryParameters["creatorId"]?.toIntOrNull()

                val response = orderService.getShippingStatus(userId, orderId, creatorId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            patch("/{id}/shipping/status", {
                summary = "배송 상태 변경 (Creator)"
                tags("Orders")
                request {
                    pathParameter<Int>("id") {
                        description = "주문 ID"
                        required = true
                    }
                    body<UpdateShippingStatusRequest> {
                        description = "변경할 배송 상태"
                        required = true
                        example("preparing") {
                            value = UpdateShippingStatusRequest(status = ShippingStatus.PREPARING)
                        }
                        example("shipped") {
                            value = UpdateShippingStatusRequest(status = ShippingStatus.SHIPPED)
                        }
                        example("in_transit") {
                            value = UpdateShippingStatusRequest(status = ShippingStatus.IN_TRANSIT)
                        }
                        example("out_for_delivery") {
                            value = UpdateShippingStatusRequest(status = ShippingStatus.OUT_FOR_DELIVERY)
                        }
                        example("delivered") {
                            value = UpdateShippingStatusRequest(status = ShippingStatus.DELIVERED)
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 상태 전환"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "주문 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "배송 상태 변경 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@patch

                val orderId = call.getRequiredIntParam("id", Errors.Commerce.Order.INVALID_ORDER_ID)
                    ?: return@patch

                val request = call.receive<UpdateShippingStatusRequest>()
                val response = orderService.updateShippingStatus(userId, orderId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Commerce.SHIPPING_STATUS_UPDATED))
            }

            authenticate("jwt") {
                get("/admin/statistics", {
                    summary = "주문 통계 (Admin)"
                    tags("Orders")
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "통계 조회 성공"
                            body<ApiResponse<OrderStatisticsResponse>>()
                        }
                        code(HttpStatusCode.Forbidden) {
                            description = "권한 없음"
                        }
                    }
                }) {
                    call.requireAdminId() ?: return@get

                    val response = orderService.getOrderStatistics()
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }
            }
        }
    }
}
