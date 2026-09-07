package com.ninezero.features.user.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CreatorApplicationStatus
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.user.domain.CreatorApplicationService
import com.ninezero.features.user.presentation.models.request.CreatorApplicationRequest
import com.ninezero.features.user.presentation.models.request.DemoteCreatorRequest
import com.ninezero.features.user.presentation.models.request.RejectApplicationRequest
import com.ninezero.features.user.presentation.models.response.CreatorApplicationListResponse
import com.ninezero.features.user.presentation.models.response.CreatorApplicationResponse
import com.ninezero.features.user.presentation.models.response.CreatorStatisticsResponse
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

fun Route.creatorApplicationRoutes() {
    route(Constants.Endpoints.CREATOR) {
        val service by inject<CreatorApplicationService>()

        authenticate("jwt") {
            rateLimit(RateLimitName("creator")) {
                post("/apply", {
                    summary = "크리에이터 신청"
                    tags("CreatorApplications")
                    request {
                        body<CreatorApplicationRequest> {
                            description = "크리에이터 신청 정보"
                            required = true
                            example("default") {
                                value = CreatorApplicationRequest(
                                    reason = "저는 VTuber 굿즈를 직접 제작하고 판매하고 싶습니다.",
                                    portfolioUrl = "https://myportfolio.com"
                                )
                            }
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.Created) {
                            description = "신청 완료"
                            body<ApiResponse<CreatorApplicationResponse>>()
                        }
                        code(HttpStatusCode.Conflict) {
                            description = "이미 신청했거나 이미 크리에이터임"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@post

                    val request = call.receive<CreatorApplicationRequest>()
                    val response = service.applyForCreator(userId, request)
                    call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.User.APPLICATION_SUBMITTED))
                }
            }

            get("/application/me", {
                summary = "내 신청 조회"
                tags("CreatorApplications")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "신청 정보 조회 성공"
                        body<ApiResponse<CreatorApplicationResponse>>()
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "신청 내역이 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = service.getMyApplication(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            delete("/application/me", {
                summary = "신청 취소"
                tags("CreatorApplications")
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "신청 취소 완료"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "취소할 신청이 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                service.cancelApplication(userId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/revoke", {
                summary = "크리에이터 셀프 해제"
                tags("CreatorApplications")
                description = "활성 구독자·진행 중 주문 없어야 함, 플랜 비활성·상품 판매종료·글 숨김 후 일반 유저 전환"
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "해제 완료"
                        body<ApiResponse<String>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "활성 구독자 또는 진행 중 주문이 남아 있음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val response = service.revokeSelf(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }

    // 관리자 전용 API
    route(Constants.Endpoints.ADMIN_CREATOR) {
        val service by inject<CreatorApplicationService>()

        authenticate("jwt", "session-auth") {
            get("/applications", {
                summary = "신청 목록 조회 (Admin)"
                tags("CreatorApplications")
                request {
                    queryParameter<String>("status") {
                        description = "신청 상태 필터 (PENDING, APPROVED, REJECTED)"
                        required = false
                        example("default") {
                            value = "PENDING"
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
                    queryParameter<String>("search") {
                        description = "검색어 (사용자명)"
                        required = false
                        example("default") {
                            value = "rick"
                        }
                    }
                    queryParameter<String>("sortBy") {
                        description = "정렬 컬럼 (username, status, createdAt, reviewedAt)"
                        required = false
                        example("default") {
                            value = "createdAt"
                        }
                    }
                    queryParameter<String>("sortOrder") {
                        description = "정렬 방향 (asc, desc)"
                        required = false
                        example("default") {
                            value = "desc"
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "신청 목록 조회 성공"
                        body<ApiResponse<CreatorApplicationListResponse>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "관리자 권한 필요"
                    }
                }
            }) {
                val adminId = call.requireAdminId() ?: return@get

                val statusParam = call.getOptionalStringParam("status")
                val status = statusParam?.let { runCatching { CreatorApplicationStatus.valueOf(it.uppercase()) }.getOrNull() }
                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val search = call.getOptionalStringParam("search")
                val sortBy = call.getOptionalStringParam("sortBy")
                val sortOrder = call.getOptionalStringParam("sortOrder")

                val response = service.getApplications(adminId, status, page, limit, search, sortBy, sortOrder)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/applications/{id}/approve", {
                summary = "신청 승인 (Admin)"
                tags("CreatorApplications")
                request {
                    pathParameter<Int>("id") {
                        description = "신청 ID"
                        required = true
                        example("default") {
                            value = 1
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "승인 완료"
                        body<ApiResponse<CreatorApplicationResponse>>()
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "신청을 찾을 수 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "관리자 권한 필요"
                    }
                }
            }) {
                val adminId = call.requireAdminId() ?: return@post

                val applicationId = call.getRequiredIntParam("id", Errors.Common.INVALID_ID)
                    ?: return@post

                val response = service.approveApplication(applicationId, adminId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/applications/{id}/reject", {
                summary = "신청 거절 (Admin)"
                tags("CreatorApplications")
                request {
                    pathParameter<Int>("id") {
                        description = "신청 ID"
                        required = true
                        example("default") {
                            value = 1
                        }
                    }
                    body<RejectApplicationRequest> {
                        description = "거절 사유"
                        required = true
                        example("default") {
                            value = RejectApplicationRequest(
                                rejectionReason = "포트폴리오가 부족합니다."
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "거절 완료"
                        body<ApiResponse<CreatorApplicationResponse>>()
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "신청을 찾을 수 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "관리자 권한 필요"
                    }
                }
            }) {
                val adminId = call.requireAdminId() ?: return@post

                val applicationId = call.getRequiredIntParam("id", Errors.Common.INVALID_ID)
                    ?: return@post

                val request = call.receive<RejectApplicationRequest>()
                val response = service.rejectApplication(applicationId, adminId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/{userId}/demote", {
                summary = "크리에이터 강등 (Admin)"
                tags("CreatorApplications")
                request {
                    pathParameter<Int>("userId") {
                        description = "사용자 ID"
                        required = true
                        example("default") {
                            value = 1
                        }
                    }
                    body<DemoteCreatorRequest> {
                        description = "강등 사유"
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "강등 완료"
                        body<ApiResponse<String>>()
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "사용자를 찾을 수 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "관리자 권한 필요"
                    }
                }
            }) {
                val adminId = call.requireAdminId() ?: return@post

                val userId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@post

                val request = call.receive<DemoteCreatorRequest>()
                val response = service.demoteCreator(userId, request.reason, adminId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/statistics", {
                summary = "크리에이터 통계 조회 (Admin)"
                tags("CreatorApplications")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "통계 조회 성공"
                        body<ApiResponse<CreatorStatisticsResponse>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "권한 없음"
                    }
                }
            }) {
                val adminId = call.requireAdminId() ?: return@get

                val response = service.getCreatorStatistics(adminId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
