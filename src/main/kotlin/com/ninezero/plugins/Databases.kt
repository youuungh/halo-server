package com.ninezero.plugins

import com.ninezero.core.common.config.DotenvConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.koin.ktor.ext.getKoin

fun Application.configureDatabases() {
    val dataSource = initDB()
    runFlyway(dataSource)
    log.info("데이터베이스 초기화 완료")
}

private fun Application.initDB(): HikariDataSource {
    val url = DotenvConfig["DATABASE_URL"] ?: "jdbc:h2:file:./data/hub"
    val user = DotenvConfig["DATABASE_USER"] ?: "sa"
    val driver = DotenvConfig["DATABASE_DRIVER"] ?: "org.h2.Driver"
    val password = DotenvConfig["DATABASE_PASSWORD"] ?: ""

    val hikariConfig = createHikariConfig(url, user, driver, password)
    val dataSource = HikariDataSource(hikariConfig)

    Database.connect(dataSource)
    getKoin().declare(dataSource)

    log.info("HikariCP 커넥션 풀이 초기화되었습니다. (Pool: ${hikariConfig.poolName}, Max: ${hikariConfig.maximumPoolSize})")

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
        connectionTimeout = 30000       // 연결 타임아웃
        idleTimeout = 600000            // 유휴 커넥션 타임아웃
        maxLifetime = 1800000           // 커넥션 최대 생명주기

        isAutoCommit = true
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"

        leakDetectionThreshold = 60000  // 커넥션 누수 감지

        poolName = "HubHikariPool"
    }
}

private fun Application.runFlyway(dataSource: HikariDataSource) {
    val flyway = Flyway.configure()
        .dataSource(dataSource)
        .baselineOnMigrate(true)
        .load()

    try {
        flyway.migrate()
        log.info("Flyway 마이그레이션 완료")
    } catch (e: Exception) {
        log.error("Flyway 마이그레이션 실패: ${e.message}", e)
        throw e
    }
}