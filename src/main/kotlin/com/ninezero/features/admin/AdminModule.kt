package com.ninezero.features.admin

import com.ninezero.features.admin.domain.MonitoringService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val adminModule = module {
    singleOf(::MonitoringService)
}
