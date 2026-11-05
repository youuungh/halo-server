package com.ninezero.features.admin.domain

import com.ninezero.features.admin.presentation.models.response.*
import com.sun.management.OperatingSystemMXBean
import io.micrometer.core.instrument.MeterRegistry
import java.io.File
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
                serverErrorRate = 0.0,
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
        val serverErrorCount = httpTimers
            .filter { it.id.getTag("status")?.startsWith("5") == true }
            .sumOf { it.count() }
        val serverErrorRate = if (totalRequests > 0) serverErrorCount.toDouble() / totalRequests else 0.0

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
            serverErrorRate = serverErrorRate,
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
        val (memoryTotal, memoryAvailable) = serverMemory()
        val root = File("/")

        return SystemMetricsResponse(
            uptime = ManagementFactory.getRuntimeMXBean().uptime,
            cpuUsage = registry.gaugeValue("system.cpu.usage") * 100,
            processMemory = processMemory,
            memoryTotal = memoryTotal,
            memoryAvailable = memoryAvailable,
            diskTotal = root.totalSpace,
            diskUsable = root.usableSpace
        )
    }

    /** Linux는 MemAvailable(캐시 제외), 그 외는 OS 기본값 */
    private fun serverMemory(): Pair<Long, Long> {
        val meminfo = File("/proc/meminfo")
        if (meminfo.canRead()) {
            val kb = meminfo.readLines()
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    parts.getOrNull(1)?.toLongOrNull()?.let { parts[0].trimEnd(':') to it * 1024 }
                }
                .toMap()
            val total = kb["MemTotal"]
            val available = kb["MemAvailable"]
            if (total != null && available != null) return total to available
        }
        val os = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
        return (os?.totalMemorySize ?: 0) to (os?.freeMemorySize ?: 0)
    }

    private fun MeterRegistry.gaugeValue(name: String): Double = find(name).gauge()?.value() ?: 0.0
}
