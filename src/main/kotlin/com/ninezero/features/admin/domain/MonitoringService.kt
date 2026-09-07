package com.ninezero.features.admin.domain

import com.ninezero.core.common.util.gaugeValue
import com.ninezero.features.admin.presentation.models.response.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
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
            uptime = runtime.uptime,
            version = System.getProperty("java.version") ?: "unknown"
        )
    }

    private fun getHttpMetrics(): HttpMetricsResponse {
        val allTimers = registry.find("http.server.requests").timers()

        val httpTimers = allTimers.ifEmpty {
            registry.meters
                .filterIsInstance<Timer>()
                .filter { it.id.name.contains("http", ignoreCase = true) }
        }

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
                val statusTag = timer.id.tags.find { it.key == "status" }?.value
                statusTag?.startsWith("4") == true || statusTag?.startsWith("5") == true
            }
            .sumOf { it.count() }
        val errorRate = if (totalRequests > 0) errorCount.toDouble() / totalRequests else 0.0

        data class EndpointStat(var count: Int = 0, var totalTimeMs: Double = 0.0)
        val endpointMap = mutableMapOf<String, EndpointStat>()

        for (timer in httpTimers) {
            val uri = timer.id.tags.find { it.key == "uri" }?.value
                ?: timer.id.tags.find { it.key == "path" }?.value
                ?: timer.id.tags.find { it.key == "route" }?.value
                ?: timer.id.tags.find { it.key == "endpoint" }?.value
                ?: run {
                    val method = timer.id.tags.find { it.key == "method" }?.value ?: "UNKNOWN"
                    val status = timer.id.tags.find { it.key == "status" }?.value ?: "UNKNOWN"
                    "$method $status"
                }

            val cleanUri = uri.replace(Regex("/\\d+"), "/{id}")

            val agg = endpointMap.getOrPut(cleanUri) { EndpointStat() }
            agg.count += timer.count().toInt()
            agg.totalTimeMs += timer.totalTime(TimeUnit.MILLISECONDS)
        }

        val endpointStats = endpointMap
            .map { (endpoint, agg) ->
                EndpointStatResponse(
                    endpoint = endpoint,
                    count = agg.count,
                    avgTime = if (agg.count > 0) agg.totalTimeMs / agg.count else 0.0
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
        val poolName = registry.find("hikari.connections.active").gauge()
            ?.id?.tags?.find { it.key == "pool" }?.value ?: ""

        return DatabaseMetricsResponse(
            activeConnections = registry.gaugeValue("hikari.connections.active", "pool" to poolName).toInt(),
            idleConnections = registry.gaugeValue("hikari.connections.idle", "pool" to poolName).toInt(),
            totalConnections = registry.gaugeValue("hikari.connections", "pool" to poolName).toInt(),
            maxConnections = registry.gaugeValue("hikari.connections.max", "pool" to poolName).toInt()
        )
    }

    fun getSystemMetrics(): SystemMetricsResponse {
        val processMemory = registry.gaugeValue("jvm.memory.used", "area" to "heap") +
                registry.gaugeValue("jvm.memory.used", "area" to "nonheap")

        return SystemMetricsResponse(
            uptime = (registry.gaugeValue("process.uptime") * 1000).toLong(),
            cpuUsage = registry.gaugeValue("system.cpu.usage") * 100,
            processMemory = processMemory.toLong()
        )
    }
}