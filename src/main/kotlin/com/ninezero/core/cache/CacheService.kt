package com.ninezero.core.cache

import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.logger
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisException
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanFlow
import io.lettuce.core.SetArgs
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class CacheService(
    private val isEnabled: Boolean = DotenvConfig.getOrDefault("REDIS_ENABLED", "true").toBoolean(),
    private val commands: () -> RedisCoroutinesCommands<String, String> = { RedisConfig.commands }
) {
    private val logger = logger()

    // 장애 뒤 연결 대기 없이 캐시를 건너뛸 시각
    @Volatile
    private var skipUntilMillis = 0L

    private companion object {
        val SKIP_AFTER_FAILURE = 30.seconds
    }

    init {
        if (!isEnabled) {
            logger.warn("Redis 캐싱이 비활성화되었습니다")
        } else {
            logger.info("Redis 캐싱이 활성화되었습니다")
        }
    }

    suspend fun <T> get(key: String, deserializer: (String) -> T?): T? =
        redis("캐시 조회 실패: key=$key") { commands().get(key)?.let(deserializer) }

    suspend fun <T> set(
        key: String,
        value: T,
        serializer: (T) -> String,
        ttl: Duration = 1.hours
    ) {
        redis("캐시 저장 실패: key=$key") {
            val json = serializer(value)
            commands().set(key, json, SetArgs().ex(ttl.inWholeSeconds))
            logger.debug("캐시 저장 성공: key={}, ttl={}s", key, ttl.inWholeSeconds)
        }
    }

    suspend inline fun <reified T> getJson(key: String): T? =
        get(key) { json ->
            try {
                Json.decodeFromString<T>(json)
            } catch (_: Exception) {
                null
            }
        }

    suspend inline fun <reified T> setJson(key: String, value: T, ttl: Duration = 1.hours) {
        set(key, value, { Json.encodeToString(it) }, ttl)
    }

    suspend fun delete(key: String) {
        redis("캐시 삭제 실패: key=$key") {
            commands().del(key)
            logger.debug("캐시 삭제: key={}", key)
        }
    }

    suspend fun deletePattern(pattern: String) {
        redis("패턴 캐시 삭제 실패: pattern=$pattern") {
            val keys = ScanFlow.scan(commands(), ScanArgs.Builder.matches(pattern).limit(100)).toList()
            keys.chunked(100).forEach { commands().del(*it.toTypedArray()) }

            if (keys.isNotEmpty()) {
                logger.debug("패턴 캐시 삭제: pattern={}, count={}", pattern, keys.size)
            }
        }
    }

    /** 장애 시 건너뛰는 Redis 호출 */
    private suspend fun <T> redis(failureLog: String, block: suspend () -> T): T? {
        if (!isEnabled || System.currentTimeMillis() < skipUntilMillis) return null

        return withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: RedisCommandExecutionException) {
                logger.warn("{}, error={}", failureLog, e.message)  // 서버의 명령 오류 응답은 장애 아님
                null
            } catch (e: RedisException) {
                // 연결 실패·명령 타임아웃·끊긴 동안 거부
                skipUntilMillis = System.currentTimeMillis() + SKIP_AFTER_FAILURE.inWholeMilliseconds
                logger.warn("{}, {}초간 Redis 건너뜀: error={}", failureLog, SKIP_AFTER_FAILURE.inWholeSeconds, e.message)
                null
            } catch (e: Exception) {
                logger.warn("{}, error={}", failureLog, e.message)
                null
            }
        }
    }
}
