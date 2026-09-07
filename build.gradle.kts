import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "com.ninezero"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-opt-in=io.lettuce.core.ExperimentalLettuceCoroutinesApi")
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.DelicateCoroutinesApi")
    }
}

dependencies {
    // * 서버 핵심
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.netty)

    // * HTTP 기능
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.status.pages)

    // * 보안
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.csrf)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.bcrypt)
    implementation(libs.jwks.rsa)

    // * 요청 처리
    implementation(libs.ktor.server.request.validation)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)

    // * API 문서
    // implementation(libs.ktor.server.openapi)
    // implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.swagger.ui)
    implementation(libs.ktor.openapi)
    implementation(libs.schema.kenerator.core)
    implementation(libs.schema.kenerator.reflection)
    implementation(libs.schema.kenerator.serialization)
    implementation(libs.schema.kenerator.swagger)
    implementation(libs.swagger.models)

    // * 모니터링
    implementation(libs.khealth)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.logback.classic)

    // * 데이터베이스
    implementation(libs.postgresql)
    implementation(libs.h2)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.kotlinx.datetime)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    // * 유틸리티
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.dotenv.kotlin)

    // * 의존성 주입
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // * 실시간 기능
    implementation(libs.ktor.server.websockets)
    implementation(libs.supabase.storage)

    // * Redis
    implementation(libs.lettuce.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

    // * 이메일 발송 (Brevo API)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // * Firebase (FCM)
    implementation(libs.firebase.admin)

    // * 테스트
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("ENVIRONMENT", "test")
    systemProperty("JWT_SECRET", "test-jwt-secret")
    systemProperty("DATABASE_URL", "jdbc:h2:mem:app-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
    systemProperty("DATABASE_DRIVER", "org.h2.Driver")
    systemProperty("DATABASE_USER", "sa")
    systemProperty("DATABASE_PASSWORD", "")
    systemProperty("REDIS_ENABLED", "false")
}

// Flyway SPI 파일 병합 설정 (Docker Shadow JAR 문제 해결)
tasks.named<ShadowJar>("shadowJar") {
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
