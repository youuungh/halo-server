package com.ninezero.features.admin

import com.ninezero.features.admin.domain.MonitoringService
import com.ninezero.plugins.appMicrometerRegistry
import io.micrometer.core.instrument.MeterRegistry
import org.koin.dsl.module

val adminModule = module {
    single<MeterRegistry> { appMicrometerRegistry }
    single { MonitoringService(get()) }
}
