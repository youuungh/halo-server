package com.ninezero.plugins

import com.ninezero.core.common.config.DotenvConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

fun Application.configureDatabases() {
    val dataSource = initDB()
    runFlyway(dataSource)
    // 요청·백그라운드 작업이 끝난 뒤 커넥션 반납
    monitor.subscribe(ApplicationStopped) { dataSource.close() }
    log.info("데이터베이스 초기화 완료")
}

private fun Application.initDB(): HikariDataSource {
    val url = DotenvConfig.getOrDefault("DATABASE_URL", "jdbc:h2:file:./data/hub")
    val user = DotenvConfig.getOrDefault("DATABASE_USER", "sa")
    val driver = DotenvConfig.getOrDefault("DATABASE_DRIVER", "org.h2.Driver")
    val password = DotenvConfig.getOrDefault("DATABASE_PASSWORD", "")

    val hikariConfig = createHikariConfig(url, user, driver, password)
    val dataSource = HikariDataSource(hikariConfig)

    Database.connect(dataSource)

    log.info("HikariCP 커넥션 풀이 초기화되었습니다. (Pool: {}, Max: {})", hikariConfig.poolName, hikariConfig.maximumPoolSize)

    return dataSource
}

private fun createHikariConfig(url: String, user: String, driver: String, password: String): HikariConfig {
    return HikariConfig().apply {
        jdbcUrl = url
        username = user
        driverClassName = driver
        this.password = password

        maximumPoolSize = 10            // 최대 커넥션 수
        minimumIdle = 5                 // 최소 유휴 커넥션 수

        transactionIsolation = "TRANSACTION_READ_COMMITTED"  // 행 잠금으로 정합 유지, PG 직렬화 실패 회피

        leakDetectionThreshold = 60000  // 커넥션 누수 감지

        poolName = "HubHikariPool"
        metricRegistry = appMicrometerRegistry  // 커넥션 풀 지표
    }
}

private fun Application.runFlyway(dataSource: HikariDataSource) {
    val flyway = Flyway.configure()
        .dataSource(dataSource)
        .baselineOnMigrate(true)
        .load()

    flyway.migrate()
    log.info("Flyway 마이그레이션 완료")
}
