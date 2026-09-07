package com.ninezero.core.common.config

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.DotenvException
import io.github.cdimascio.dotenv.dotenv

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

    fun getRequired(key: String): String = get(key)
        ?: throw IllegalStateException("$key 이 설정되지 않았습니다")
}
