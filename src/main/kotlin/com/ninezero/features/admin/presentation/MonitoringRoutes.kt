package com.ninezero.features.admin.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.getOptionalIntParam
import com.ninezero.core.common.util.getOptionalStringParam
import com.ninezero.core.common.util.requireAdminId
import com.ninezero.features.admin.domain.LogService
import com.ninezero.features.admin.domain.MonitoringService
import com.ninezero.features.admin.presentation.models.response.HealthStatusResponse
import com.ninezero.features.admin.presentation.models.response.LogQueryResponse
import com.ninezero.features.admin.presentation.models.response.MonitoringMetricsResponse
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.monitoringRoutes() {
    route(Constants.Endpoints.ADMIN_MONITORING) {
        val monitoringService by inject<MonitoringService>()
        val logService by inject<LogService>()

        authenticate("jwt") {
            get("/metrics", {
                summary = "모니터링 메트릭 조회 (Admin)"
                tags("Monitoring")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "모니터링 메트릭"
                        body<ApiResponse<MonitoringMetricsResponse>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "관리자 권한 필요"
                    }
                }
            }) {
                call.requireAdminId() ?: return@get

                val response = monitoringService.getMonitoringMetrics()
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/health", {
                summary = "헬스 상태 조회 (Admin)"
                tags("Monitoring")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "헬스 상태"
                        body<ApiResponse<HealthStatusResponse>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "관리자 권한 필요"
                    }
                }
            }) {
                call.requireAdminId() ?: return@get

                val response = monitoringService.getHealthStatus()
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/logs", {
                summary = "서버 로그 조회 (Admin)"
                tags("Monitoring")
                request {
                    queryParameter<Int>("minutes") {
                        description = "기간(분) 15/60/360/1440 (기본값: 60)"
                        required = false
                    }
                    queryParameter<String>("level") {
                        description = "ERROR/WARN/INFO, 생략 시 전체"
                        required = false
                    }
                    queryParameter<String>("source") {
                        description = "로거 이름, 생략 시 전체"
                        required = false
                    }
                    queryParameter<String>("q") {
                        description = "메시지·스택 검색어"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "최대 줄 수 (기본값: 200, 최대 500)"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "서버 로그"
                        body<ApiResponse<LogQueryResponse>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "관리자 권한 필요"
                    }
                }
            }) {
                call.requireAdminId() ?: return@get

                val response = logService.query(
                    minutes = call.getOptionalIntParam("minutes"),
                    level = call.getOptionalStringParam("level"),
                    source = call.getOptionalStringParam("source"),
                    keyword = call.getOptionalStringParam("q"),
                    limit = call.getOptionalIntParam("limit")
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
