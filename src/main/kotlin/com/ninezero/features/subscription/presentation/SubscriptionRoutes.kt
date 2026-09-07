package com.ninezero.features.subscription.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.SubscriptionStatus
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.getIntParam
import com.ninezero.core.common.util.getOptionalIntParam
import com.ninezero.core.common.util.getOptionalStringParam
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.core.common.util.requireUserId
import com.ninezero.features.subscription.domain.SubscriptionPaymentService
import com.ninezero.features.subscription.domain.SubscriptionService
import com.ninezero.features.subscription.presentation.models.request.SubscribeRequest
import com.ninezero.features.subscription.presentation.models.response.SubscriberListResponse
import com.ninezero.features.subscription.presentation.models.response.SubscriptionListResponse
import com.ninezero.features.subscription.presentation.models.response.SubscriptionResponse
import com.ninezero.features.subscription.presentation.models.response.SubscriptionStatusResponse
import com.ninezero.features.subscription.presentation.models.response.UserTierResponse
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.subscriptionRoutes() {
    route(Constants.Endpoints.SUBSCRIPTIONS) {
        val subscriptionService by inject<SubscriptionService>()
        val subscriptionPaymentService by inject<SubscriptionPaymentService>()

        authenticate("jwt") {
            rateLimit(RateLimitName("payment")) {
                post({
                    summary = "구독"
                    tags("Subscriptions")
                    request {
                        body<SubscribeRequest> {
                            description = "구독 정보"
                            required = true
                            example("default") {
                                value = SubscribeRequest(
                                    planId = 1,
                                    autoRenew = true
                                )
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.Created) {
                            description = "구독 생성 성공"
                            body<ApiResponse<SubscriptionResponse>>()
                        }
                        code(HttpStatusCode.Conflict) {
                            description = "이미 구독 중"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@post

                    val request = call.receive<SubscribeRequest>()
                    val response = subscriptionService.subscribe(userId, request)
                    call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Subscription.SUBSCRIPTION_CREATED))
                }

                post("/upgrade", {
                    summary = "구독 업그레이드"
                    tags("Subscriptions")
                    description = "상위 플랜으로 변경, 차액 즉시 청구"
                    authResponse()
                }) {
                    val userId = call.requireUserId() ?: return@post
                    val request = call.receive<SubscribeRequest>()
                    val response = subscriptionService.upgradeSubscription(userId, request)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response, "구독이 업그레이드되었습니다."))
                }

                get("/my", {
                    summary = "내 구독 목록 조회"
                    tags("Subscriptions")
                    request {
                        queryParameter<String>("status") {
                            description = "구독 상태 필터 (ACTIVE, CANCELLED, EXPIRED)"
                            required = false
                            example("default") {
                                value = "ACTIVE"
                            }
                        }
                        queryParameter<Int>("page") {
                            description = "페이지 번호"
                            required = false
                            example("default") {
                                value = 1
                            }
                        }
                        queryParameter<Int>("limit") {
                            description = "페이지 크기"
                            required = false
                            example("default") {
                                value = 20
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "구독 목록 조회 성공"
                            body<ApiResponse<SubscriptionListResponse>>()
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@get

                    val statusParam = call.getOptionalStringParam("status")
                    val status = statusParam?.let {
                        try {
                            SubscriptionStatus.valueOf(it.uppercase())
                        } catch (_: Exception) {
                            null
                        }
                    }

                    val page = call.getIntParam("page", 1)
                    val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                    val response = subscriptionService.getMySubscriptions(userId, status, page, limit)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                get("/subscribers", {
                    summary = "내 구독자 목록 조회 (Creator)"
                    tags("Subscriptions")
                    request {
                        queryParameter<Int>("page") {
                            description = "페이지 번호"
                            required = false
                            example("default") {
                                value = 1
                            }
                        }
                        queryParameter<Int>("limit") {
                            description = "페이지 크기"
                            required = false
                            example("default") {
                                value = 20
                            }
                        }
                        queryParameter<String>("search") {
                            description = "검색어 (username, displayName 대소문자 무시 부분 일치)"
                            required = false
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "구독자 목록 조회 성공"
                            body<ApiResponse<SubscriberListResponse>>()
                        }
                        code(HttpStatusCode.Forbidden) {
                            description = "크리에이터 권한 필요"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@get

                    val page = call.getIntParam("page", 1)
                    val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                    val search = call.request.queryParameters["search"]

                    val response = subscriptionService.getMySubscribers(userId, page, limit, search)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                get("/{subscriptionId}", {
                    summary = "구독 상세 조회"
                    tags("Subscriptions")
                    request {
                        pathParameter<Int>("subscriptionId") {
                            description = "구독 ID"
                            required = true
                            example("default") {
                                value = 1
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "구독 조회 성공"
                            body<ApiResponse<SubscriptionResponse>>()
                        }
                        code(HttpStatusCode.NotFound) {
                            description = "구독을 찾을 수 없음"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@get

                    val subscriptionId = call.getRequiredIntParam("subscriptionId", Errors.Subscription.INVALID_SUBSCRIPTION_ID)
                        ?: return@get

                    val response = subscriptionService.getSubscriptionById(userId, subscriptionId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                get("/check/{creatorId}", {
                    summary = "크리에이터 구독 상태 확인"
                    tags("Subscriptions")
                    request {
                        pathParameter<Int>("creatorId") {
                            description = "크리에이터 ID"
                            required = true
                            example("default") {
                                value = 1
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "구독 상태 확인 성공"
                            body<ApiResponse<SubscriptionStatusResponse>>()
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@get

                    val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                        ?: return@get

                    val response = subscriptionService.checkSubscriptionStatus(userId, creatorId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                get("/my-tier/{creatorId}", {
                    summary = "내 구독 티어 확인"
                    tags("Subscriptions")
                    request {
                        pathParameter<Int>("creatorId") {
                            description = "크리에이터 ID"
                            required = true
                            example("default") {
                                value = 1
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "티어 확인 성공"
                            body<ApiResponse<UserTierResponse>>()
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@get

                    val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                        ?: return@get

                    val response = subscriptionService.getMyTierForCreator(userId, creatorId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                delete("/{subscriptionId}", {
                    summary = "구독 취소"
                    tags("Subscriptions")
                    request {
                        pathParameter<Int>("subscriptionId") {
                            description = "구독 ID"
                            required = true
                            example("default") {
                                value = 1
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "구독 취소 성공"
                            body<ApiResponse<SubscriptionResponse>>()
                        }
                        code(HttpStatusCode.NotFound) {
                            description = "구독을 찾을 수 없음"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@delete

                    val subscriptionId = call.getRequiredIntParam("subscriptionId", Errors.Subscription.INVALID_SUBSCRIPTION_ID)
                        ?: return@delete

                    val response = subscriptionService.cancelSubscription(userId, subscriptionId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                patch("/{subscriptionId}/auto-renew", {
                    summary = "자동갱신 설정 변경"
                    tags("Subscriptions")
                    request {
                        pathParameter<Int>("subscriptionId") {
                            description = "구독 ID"
                            required = true
                            example("default") {
                                value = 1
                            }
                        }
                        queryParameter<Boolean>("autoRenew") {
                            description = "자동 갱신 여부"
                            required = true
                            example("default") {
                                value = true
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "자동 갱신 설정 변경 성공"
                            body<ApiResponse<SubscriptionResponse>>()
                        }
                        code(HttpStatusCode.BadRequest) {
                            description = "autoRenew 파라미터 누락"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@patch

                    val subscriptionId = call.getRequiredIntParam("subscriptionId", Errors.Subscription.INVALID_SUBSCRIPTION_ID)
                        ?: return@patch

                    val autoRenew = call.getOptionalStringParam("autoRenew")?.toBoolean()
                        ?: return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<Unit>(Errors.Subscription.SUBSCRIPTION_AUTO_RENEW_PARAM_REQUIRED)
                        )

                    val response = subscriptionService.updateAutoRenew(userId, subscriptionId, autoRenew)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                get("/access/{creatorId}", {
                    summary = "콘텐츠 접근 권한 확인"
                    tags("Subscriptions")
                    request {
                        pathParameter<Int>("creatorId") {
                            description = "크리에이터 ID"
                            required = true
                            example("default") {
                                value = 1
                            }
                        }
                        queryParameter<Int>("planId") {
                            description = "플랜 ID (선택)"
                            required = false
                            example("default") {
                                value = 1
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "접근 권한 확인 성공"
                            body<ApiResponse<Map<String, Boolean>>>()
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@get

                    val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                        ?: return@get

                    val planId = call.getOptionalIntParam("planId")

                    val response = subscriptionService.canAccessContent(userId, creatorId, planId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                // 실제 토스 청구
                post("/payment/mock", {
                    summary = "구독 최초 결제"
                    tags("Subscriptions")
                    description = "빌링키 자동 청구, 실결제"
                    request {
                        queryParameter<Int>("subscriptionId") {
                            description = "구독 ID"
                            required = true
                            example("default") {
                                value = 1
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "결제 처리 성공"
                            body<ApiResponse<Any>>()
                        }
                        code(HttpStatusCode.NotFound) {
                            description = "구독을 찾을 수 없음"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@post

                    val subscriptionId =
                        call.getRequiredIntParam("subscriptionId", Errors.Subscription.INVALID_SUBSCRIPTION_ID)
                            ?: return@post

                    val response = subscriptionPaymentService.processInitialPayment(subscriptionId, userId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }

                get("/{subscriptionId}/payments", {
                    summary = "구독 결제 내역 조회"
                    tags("Subscriptions")
                    request {
                        pathParameter<Int>("subscriptionId") {
                            description = "구독 ID"
                            required = true
                            example("default") {
                                value = 1
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.OK) {
                            description = "결제 내역 조회 성공"
                            body<ApiResponse<Any>>()
                        }
                        code(HttpStatusCode.NotFound) {
                            description = "구독을 찾을 수 없음"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@get

                    val subscriptionId = call.getRequiredIntParam("subscriptionId", Errors.Subscription.INVALID_SUBSCRIPTION_ID)
                        ?: return@get

                    val response = subscriptionPaymentService.getPaymentHistory(subscriptionId, userId)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }
            }
        }
    }
}
