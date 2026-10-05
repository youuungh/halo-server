package com.ninezero.core.common.config

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.DotenvException
import io.github.cdimascio.dotenv.dotenv
import org.slf4j.LoggerFactory

object DotenvConfig {
    private val dotenv: Dotenv? = try {
        dotenv {
            ignoreIfMissing = true
            systemProperties = true
        }
    } catch (_: DotenvException) {
        null
    }

    operator fun get(key: String): String? =
        System.getProperty(key)
            ?: System.getenv(key)
            ?: dotenv?.get(key)

    fun getOrDefault(key: String, default: String): String = get(key) ?: default

    val isProd: Boolean by lazy {
        val environment = get("ENVIRONMENT")
        if (environment == null) {
            LoggerFactory.getLogger(DotenvConfig::class.java)
                .warn("ENVIRONMENT 값이 없어 개발 환경으로 동작합니다")
        }
        environment == "prod"
    }

    fun getRequired(key: String): String = get(key)
        ?: throw IllegalStateException("$key 이 설정되지 않았습니다")
}
