package com.ninezero

import com.ninezero.core.cache.RedisConfig
import com.ninezero.core.fcm.FcmConfig
import com.ninezero.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.koin.ktor.ext.inject

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    FcmConfig.initialize()

    configureFrameworks()
    configureDatabases()
    configureMonitoring()
    configureSecurity()
    configureHTTP()
    configureSerialization()
    configureSockets()
    configureSwagger()
    configureRouting()
    configureScheduler()

    val backgroundScope by inject<CoroutineScope>()
    monitor.subscribe(ApplicationStopping) {
        // 백그라운드 스코프 우선 취소
        backgroundScope.cancel()
        RedisConfig.close()
    }
}
