package com.ninezero

import io.ktor.server.testing.*
import org.junit.jupiter.api.Disabled
import kotlin.test.Test

class ApplicationTest {

    // 앱 모듈 부트는 Supabase 스토리지 등 .env 실환경 설정이 필수라(StorageConfig 초기화)
    // gradle test JVM에서는 성립하지 않는 스모크 골격. 로직 커버리지는 service/* 테스트가 담당한다.
    @Disabled("전체 앱 부트는 .env(Supabase) 필요 — 단위 테스트 환경에서 실행 불가")
    @Test
    fun testRoot() = testApplication {
        application {
            module()
        }
    }

}
