package com.ninezero.features.point.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.PointType
import com.ninezero.core.common.util.*
import com.ninezero.features.point.domain.PointService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDateTime
import org.koin.ktor.ext.inject

fun Route.pointRoutes() {
    route(Constants.Endpoints.POINTS) {
        val pointService by inject<PointService>()

        authenticate("jwt") {
            get("/balance", {
                summary = "포인트 잔액 조회"
                tags("Points")
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "포인트 계정 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = pointService.getBalance(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/history", {
                summary = "포인트 내역 조회"
                tags("Points")
                request {
                    queryParameter<String>("type") {
                        description = "내역 유형 필터 (EARN/USE/EXPIRE/REFUND, 미지정 시 전체)"
                        required = false
                    }
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<String>("startDate") {
                        description = "조회 시작일 (ISO 8601, 예: 2026-02-01T00:00:00)"
                        required = false
                    }
                    queryParameter<String>("endDate") {
                        description = "조회 종료일 (ISO 8601, 예: 2026-02-28T23:59:59)"
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
                val startDate = call.request.queryParameters["startDate"]?.let {
                    runCatching { LocalDateTime.parse(it) }.getOrNull()
                }
                val endDate = call.request.queryParameters["endDate"]?.let {
                    runCatching { LocalDateTime.parse(it) }.getOrNull()
                }
                // 무효값은 전체로 처리
                val type = call.request.queryParameters["type"]?.let { raw ->
                    PointType.entries.find { it.name == raw }
                }

                val response = pointService.getHistory(userId, page, limit, startDate, endDate, type)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/expire", {
                summary = "포인트 수동 만료 (Admin)"
                tags("Points")
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "관리자 권한 필요"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                call.requireAdminId() ?: return@post

                val expiredCount = pointService.expirePoints()
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        mapOf(
                            "expiredCount" to expiredCount,
                            "message" to Messages.Point.POINT_EXPIRED_COUNT.format(expiredCount)
                        )
                    )
                )
            }
        }
    }
}
