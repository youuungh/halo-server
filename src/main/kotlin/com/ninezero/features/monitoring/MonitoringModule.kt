package com.ninezero.features.monitoring

import com.ninezero.features.monitoring.domain.LogService
import com.ninezero.features.monitoring.domain.MonitoringService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val monitoringModule = module {
    singleOf(::MonitoringService)
    singleOf(::LogService)
}
