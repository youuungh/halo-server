package com.ninezero.plugins

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.logger
import io.ktor.server.application.*
import io.ktor.server.application.hooks.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.request.*
import io.ktor.util.*
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

val appMicrometerRegistry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

private val callStartKey = AttributeKey<Long>("CallStartNanos")
private val callLoggedKey = AttributeKey<Unit>("CallLogged")

// 상태 코드로 수준을 고르는 요청 로그
private val RequestLogging = createApplicationPlugin("RequestLogging") {
    val logger = logger("HTTP")

    onCall { call -> call.attributes.put(callStartKey, System.nanoTime()) }

    on(ResponseSent) { call ->
        val path = call.request.path()
        // 대시보드 주기 조회가 로그 화면을 덮지 않게
        if (path.startsWith(Constants.Endpoints.ADMIN_MONITORING)) return@on
        // 응답이 두 번 나가는 404도 한 줄만
        if (call.attributes.contains(callLoggedKey)) return@on
        call.attributes.put(callLoggedKey, Unit)

        val status = call.response.status()
        val elapsedMs = call.attributes.getOrNull(callStartKey)?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0
        // LogService REQUEST 정규식과 같아야 함
        val message = "${status ?: "Unhandled"}: ${call.request.httpMethod.value} - $path in ${elapsedMs}ms"
        val code = status?.value ?: 0
        when {
            code >= 500 -> logger.error(message)
            // 토큰 갱신·외부 스캐너로 늘 생겨 경고 제외
            code == 401 || code == 404 -> logger.info(message)
            code >= 400 -> logger.warn(message)
            else -> logger.info(message)
        }
    }
}

fun Application.configureMonitoring() {
    install(MicrometerMetrics) {
        registry = appMicrometerRegistry

        meterBinders = listOf(
            JvmGcMetrics(),
            JvmThreadMetrics(),
            ProcessorMetrics()
        )
    }
    install(RequestLogging)
}
