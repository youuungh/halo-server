package com.ninezero.features.admin.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.requireAdminId
import com.ninezero.features.admin.domain.MonitoringService
import com.ninezero.features.admin.presentation.models.response.HealthStatusResponse
import com.ninezero.features.admin.presentation.models.response.MonitoringMetricsResponse
import com.ninezero.features.admin.presentation.models.response.SystemMetricsResponse
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.monitoringRoutes() {
    route(Constants.Endpoints.ADMIN_MONITORING) {
        val monitoringService by inject<MonitoringService>()

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

            get("/system", {
                summary = "시스템 리소스 조회 (Admin)"
                tags("Monitoring")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "시스템 메트릭"
                        body<ApiResponse<SystemMetricsResponse>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "관리자 권한 필요"
                    }
                }
            }) {
                call.requireAdminId() ?: return@get

                val response = monitoringService.getSystemMetrics()
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
