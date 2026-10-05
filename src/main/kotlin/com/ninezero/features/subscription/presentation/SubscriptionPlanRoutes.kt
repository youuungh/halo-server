package com.ninezero.features.subscription.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.apiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.getIntParam
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.core.common.util.requireUserId
import com.ninezero.features.subscription.domain.SubscriptionPlanService
import com.ninezero.features.subscription.presentation.models.request.SubscriptionPlanRequest
import com.ninezero.features.subscription.presentation.models.request.UpdatePlanRequest
import com.ninezero.features.subscription.presentation.models.response.SubscriptionPlanListResponse
import com.ninezero.features.subscription.presentation.models.response.SubscriptionPlanResponse
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.subscriptionPlanRoutes() {
    route(Constants.Endpoints.SUBSCRIPTION_PLANS) {
        val subscriptionPlanService by inject<SubscriptionPlanService>()

        get("/creator/{creatorId}", {
            summary = "크리에이터 플랜 목록 조회 (공개)"
            tags("SubscriptionPlans")
            request {
                pathParameter<Int>("creatorId") {
                    description = "크리에이터 ID"
                    required = true
                }
                queryParameter<Int>("page") {
                    description = "페이지 번호 (기본값: 1)"
                    required = false
                }
                queryParameter<Int>("limit") {
                    description = "페이지 크기 (기본값: 20)"
                    required = false
                }
            }
            apiResponse()
            response {
                code(HttpStatusCode.OK) {
                    description = "플랜 목록 조회 성공"
                    body<ApiResponse<SubscriptionPlanListResponse>>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "크리에이터를 찾을 수 없음"
                }
            }
        }) {
            val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                ?: return@get

            val page = call.getIntParam("page", 1)
            val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

            val response = subscriptionPlanService.getCreatorPlans(creatorId, page, limit)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        authenticate("jwt") {
            post({
                summary = "플랜 생성 (Creator)"
                tags("SubscriptionPlans")
                request {
                    body<SubscriptionPlanRequest> {
                        description = "플랜 정보"
                        required = true
                        example("default") {
                            value = SubscriptionPlanRequest(
                                name = "베이직 플랜",
                                tier = SubscriptionPlanTier.TIER1,
                                description = "기본 혜택을 제공하는 플랜입니다.",
                                price = "5000",
                                benefits = listOf("월간 콘텐츠 접근", "독점 업데이트")
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "플랜 생성 성공"
                        body<ApiResponse<SubscriptionPlanResponse>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터 권한 필요"
                    }
                }
            }) {
                val creatorId = call.requireUserId() ?: return@post

                val request = call.receive<SubscriptionPlanRequest>()
                val response = subscriptionPlanService.createPlan(creatorId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Subscription.PLAN_CREATED))
            }

            get("/my", {
                summary = "내 플랜 목록 조회 (Creator)"
                tags("SubscriptionPlans")
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
                    code(HttpStatusCode.OK) {
                        description = "내 플랜 목록 조회 성공"
                        body<ApiResponse<SubscriptionPlanListResponse>>()
                    }
                }
            }) {
                val creatorId = call.requireUserId() ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = subscriptionPlanService.getMyPlans(creatorId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            put("/{planId}", {
                summary = "플랜 수정 (Creator)"
                tags("SubscriptionPlans")
                request {
                    pathParameter<Int>("planId") {
                        description = "플랜 ID"
                        required = true
                    }
                    body<UpdatePlanRequest> {
                        description = "수정할 플랜 정보"
                        required = true
                        example("default") {
                            value = UpdatePlanRequest(
                                name = "프리미엄 플랜",
                                price = "10000",
                                benefits = listOf("모든 콘텐츠 무제한 접근", "특별 이벤트 초대")
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "플랜 수정 성공"
                        body<ApiResponse<SubscriptionPlanResponse>>()
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "플랜을 찾을 수 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "권한 없음"
                    }
                }
            }) {
                val creatorId = call.requireUserId() ?: return@put

                val planId = call.getRequiredIntParam("planId", Errors.Subscription.INVALID_PLAN_ID)
                    ?: return@put

                val request = call.receive<UpdatePlanRequest>()
                val response = subscriptionPlanService.updatePlan(planId, creatorId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Subscription.PLAN_UPDATED))
            }

            delete("/{planId}", {
                summary = "플랜 삭제 (Creator)"
                tags("SubscriptionPlans")
                request {
                    pathParameter<Int>("planId") {
                        description = "플랜 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "플랜 삭제 성공"
                        body<ApiResponse<String>>()
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "플랜을 찾을 수 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "권한 없음"
                    }
                }
            }) {
                val creatorId = call.requireUserId() ?: return@delete

                val planId = call.getRequiredIntParam("planId", Errors.Subscription.INVALID_PLAN_ID)
                    ?: return@delete

                val response = subscriptionPlanService.deletePlan(planId, creatorId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
