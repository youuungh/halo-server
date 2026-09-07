package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.ReportTargetType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.ReportService
import com.ninezero.features.social.presentation.models.request.ReportRequest
import com.ninezero.features.social.presentation.models.response.ReportResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.reportRoutes() {
    route(Constants.Endpoints.REPORTS) {
        val reportService by inject<ReportService>()

        authenticate("jwt") {
            post({
                summary = "신고"
                tags("Reports")
                description = "포스트·댓글·사용자"
                request { body<ReportRequest>() }
                authResponse()
                response {
                    code(HttpStatusCode.Conflict) { description = "이미 신고한 대상" }
                    code(HttpStatusCode.BadRequest) { description = "자신의 콘텐츠는 신고 불가" }
                    code(HttpStatusCode.NotFound) { description = "대상을 찾을 수 없음" }
                }
            }) {
                val userId = call.requireUserId() ?: return@post
                val request = call.receive<ReportRequest>()

                val result = reportService.report(
                    reporterId = userId,
                    targetType = request.targetType,
                    targetId = request.targetId
                )

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(ReportResponse(result.reportCount, result.blinded))
                )
            }
        }
    }
}

fun Route.adminReportRoutes() {
    route(Constants.Endpoints.ADMIN_REPORTS) {
        val reportService by inject<ReportService>()

        authenticate("jwt") {
            get({
                summary = "신고 누적 대상 목록 (Admin)"
                tags("Reports")
                request {
                    queryParameter<String>("targetType") { description = "POST/COMMENT/USER, 생략 시 전체" }
                }
                paginationParams()
                authResponse()
            }) {
                call.requireAdminId() ?: return@get

                val targetType = call.request.queryParameters["targetType"]
                    ?.let { runCatching { ReportTargetType.valueOf(it) }.getOrNull() }
                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = reportService.findReportedTargets(targetType, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{targetType}/{targetId}", {
                summary = "신고 대상 상세 (Admin)"
                tags("Reports")
                description = "블라인드된 대상도 조회"
                request {
                    pathParameter<String>("targetType") { description = "POST/COMMENT/USER" }
                    pathParameter<Int>("targetId") { description = "대상 ID" }
                }
                authResponse()
            }) {
                call.requireAdminId() ?: return@get

                val targetType = call.parameters["targetType"]
                    ?.let { runCatching { ReportTargetType.valueOf(it.uppercase()) }.getOrNull() }
                    ?: throw InvalidInputException(Errors.Social.Report.INVALID_REPORT_TARGET)
                val targetId = call.getRequiredIntParam("targetId", Errors.Common.INVALID_REQUEST)
                    ?: return@get

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(reportService.findReportedTargetDetail(targetType, targetId))
                )
            }

            post("/blind", {
                summary = "수동 블라인드 (Admin)"
                tags("Reports")
                request { body<ReportRequest>() }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) { description = "블라인드 처리됨" }
                }
            }) {
                call.requireAdminId() ?: return@post
                val request = call.receive<ReportRequest>()

                reportService.blind(request.targetType, request.targetId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/unblind", {
                summary = "블라인드 해제 (Admin)"
                tags("Reports")
                request { body<ReportRequest>() }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) { description = "해제됨" }
                }
            }) {
                call.requireAdminId() ?: return@post
                val request = call.receive<ReportRequest>()

                reportService.unblind(request.targetType, request.targetId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
