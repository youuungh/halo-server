package com.ninezero.features.admin.domain

import com.ninezero.features.admin.presentation.models.response.*
import io.micrometer.core.instrument.MeterRegistry
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

class MonitoringService(private val registry: MeterRegistry) {

    fun getMonitoringMetrics(): MonitoringMetricsResponse {
        return MonitoringMetricsResponse(
            http = getHttpMetrics(),
            jvm = getJvmMetrics(),
            database = getDatabaseMetrics(),
            system = getSystemMetrics()
        )
    }

    fun getHealthStatus(): HealthStatusResponse {
        val runtime = ManagementFactory.getRuntimeMXBean()
        return HealthStatusResponse(
            status = "UP",
            uptime = runtime.uptime
        )
    }

    private fun getHttpMetrics(): HttpMetricsResponse {
        val httpTimers = registry.find("ktor.http.server.requests").timers()

        if (httpTimers.isEmpty()) {
            return HttpMetricsResponse(
                avgResponseTime = 0.0,
                errorRate = 0.0,
                totalRequests = 0,
                endpointStats = emptyList()
            )
        }

        val totalRequests = httpTimers.sumOf { it.count() }.toInt()
        val totalTimeSeconds = httpTimers.sumOf { it.totalTime(TimeUnit.SECONDS) }
        val avgResponseTimeMs = if (totalRequests > 0) {
            (totalTimeSeconds / totalRequests) * 1000
        } else {
            0.0
        }

        val errorCount = httpTimers
            .filter { timer ->
                val status = timer.id.getTag("status")
                status?.startsWith("4") == true || status?.startsWith("5") == true
            }
            .sumOf { it.count() }
        val errorRate = if (totalRequests > 0) errorCount.toDouble() / totalRequests else 0.0

        val endpointStats = httpTimers
            .groupBy { (it.id.getTag("route") ?: "UNKNOWN").replace(Regex("/\\d+"), "/{id}") }
            .map { (endpoint, timers) ->
                val count = timers.sumOf { it.count() }.toInt()
                val totalTimeMs = timers.sumOf { it.totalTime(TimeUnit.MILLISECONDS) }
                EndpointStatResponse(
                    endpoint = endpoint,
                    count = count,
                    avgTime = if (count > 0) totalTimeMs / count else 0.0
                )
            }
            .sortedByDescending { it.count }

        return HttpMetricsResponse(
            avgResponseTime = avgResponseTimeMs,
            errorRate = errorRate,
            totalRequests = totalRequests,
            endpointStats = endpointStats
        )
    }

    private fun getJvmMetrics(): JvmMetricsResponse {
        val gcTimers = registry.find("jvm.gc.pause").timers()
        val gcTime = gcTimers.sumOf { it.totalTime(TimeUnit.MILLISECONDS) }.toLong()

        val memoryMXBean = ManagementFactory.getMemoryMXBean()
        val heapMemoryUsage = memoryMXBean.heapMemoryUsage
        val nonHeapMemoryUsage = memoryMXBean.nonHeapMemoryUsage

        return JvmMetricsResponse(
            heapUsed = heapMemoryUsage.used,
            heapMax = heapMemoryUsage.max.takeIf { it > 0 } ?: heapMemoryUsage.committed,
            nonHeapUsed = nonHeapMemoryUsage.used,
            nonHeapMax = nonHeapMemoryUsage.max.takeIf { it > 0 } ?: nonHeapMemoryUsage.committed,
            threads = registry.gaugeValue("jvm.threads.live").toInt(),
            gcTime = gcTime
        )
    }

    private fun getDatabaseMetrics(): DatabaseMetricsResponse {
        return DatabaseMetricsResponse(
            activeConnections = registry.gaugeValue("hikaricp.connections.active").toInt(),
            idleConnections = registry.gaugeValue("hikaricp.connections.idle").toInt(),
            totalConnections = registry.gaugeValue("hikaricp.connections").toInt(),
            maxConnections = registry.gaugeValue("hikaricp.connections.max").toInt()
        )
    }

    private fun getSystemMetrics(): SystemMetricsResponse {
        val memoryMXBean = ManagementFactory.getMemoryMXBean()
        val processMemory = memoryMXBean.heapMemoryUsage.used + memoryMXBean.nonHeapMemoryUsage.used

        return SystemMetricsResponse(
            uptime = ManagementFactory.getRuntimeMXBean().uptime,
            cpuUsage = registry.gaugeValue("system.cpu.usage") * 100,
            processMemory = processMemory
        )
    }

    private fun MeterRegistry.gaugeValue(name: String): Double = find(name).gauge()?.value() ?: 0.0
}
