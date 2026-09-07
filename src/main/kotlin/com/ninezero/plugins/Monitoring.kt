package com.ninezero.plugins

import com.zaxxer.hikari.HikariDataSource
import dev.hayden.KHealth
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.koin.ktor.ext.inject
import org.slf4j.event.Level

val appMicrometerRegistry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

fun Application.configureMonitoring() {
    install(KHealth)

    val dataSource by inject<HikariDataSource>()

    install(MicrometerMetrics) {
        registry = appMicrometerRegistry

        meterBinders = listOf(
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            JvmThreadMetrics(),
            ProcessorMetrics(),
            UptimeMetrics(),
            HikariConnectionPoolMetrics(dataSource)
        )
    }
    install(CallLogging) {
        level = Level.INFO
    }
    routing {
        get("/metrics-micrometer") {
            call.respond(appMicrometerRegistry.scrape())
        }
    }
}

class HikariConnectionPoolMetrics(
    private val dataSource: HikariDataSource
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        val poolName = dataSource.poolName

        Gauge.builder("hikari.connections.active") {
            dataSource.hikariPoolMXBean?.activeConnections ?: 0
        }
            .tag("pool", poolName)
            .register(registry)

        Gauge.builder("hikari.connections.idle") {
            dataSource.hikariPoolMXBean?.idleConnections ?: 0
        }
            .tag("pool", poolName)
            .register(registry)

        Gauge.builder("hikari.connections") {
            dataSource.hikariPoolMXBean?.totalConnections ?: 0
        }
            .tag("pool", poolName)
            .register(registry)

        Gauge.builder("hikari.connections.pending") {
            dataSource.hikariPoolMXBean?.threadsAwaitingConnection ?: 0
        }
            .tag("pool", poolName)
            .register(registry)

        Gauge.builder("hikari.connections.max") {
            dataSource.maximumPoolSize.toDouble()
        }
            .tag("pool", poolName)
            .register(registry)

        Gauge.builder("hikari.connections.min") {
            dataSource.minimumIdle.toDouble()
        }
            .tag("pool", poolName)
            .register(registry)
    }
}
