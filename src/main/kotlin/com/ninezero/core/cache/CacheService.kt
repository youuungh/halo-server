package com.ninezero.core.cache

import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.logger
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class CacheService {
    private val logger = logger()
    private val isEnabled = DotenvConfig.getOrDefault("REDIS_ENABLED", "true").toBoolean()

    init {
        if (!isEnabled) {
            logger.warn("Redis 캐싱이 비활성화되었습니다")
        } else {
            logger.info("Redis 캐싱이 활성화되었습니다")
        }
    }

    suspend fun <T> get(key: String, deserializer: (String) -> T?): T? {
        if (!isEnabled) return null

        return withContext(Dispatchers.IO) {
            try {
                val value = RedisConfig.commands.get(key) ?: return@withContext null
                deserializer(value)
            } catch (e: Exception) {
                logger.warn("캐시 조회 실패: key=$key, error=${e.message}")
                null
            }
        }
    }

    suspend fun <T> set(
        key: String,
        value: T,
        serializer: (T) -> String,
        ttl: Duration = 1.hours
    ) {
        if (!isEnabled) return

        withContext(Dispatchers.IO) {
            try {
                val json = serializer(value)
                RedisConfig.commands.set(key, json, SetArgs().ex(ttl.inWholeSeconds))
                logger.debug("캐시 저장 성공: key=$key, ttl=${ttl.inWholeSeconds}s")
            } catch (e: Exception) {
                logger.warn("캐시 저장 실패: key=$key, error=${e.message}")
            }
        }
    }

    suspend fun delete(key: String) {
        if (!isEnabled) return

        withContext(Dispatchers.IO) {
            try {
                RedisConfig.commands.del(key)
                logger.debug("캐시 삭제: key=$key")
            } catch (e: Exception) {
                logger.warn("캐시 삭제 실패: key=$key, error=${e.message}")
            }
        }
    }

    suspend fun deletePattern(pattern: String) {
        if (!isEnabled) return

        withContext(Dispatchers.IO) {
            try {
                var deletedCount = 0
                var cursor = ScanCursor.INITIAL
                val scanArgs = ScanArgs.Builder.matches(pattern).limit(100)

                while (true) {
                    val result = RedisConfig.commands.scan(cursor, scanArgs) ?: break

                    if (result.keys.isNotEmpty()) {
                        RedisConfig.commands.del(*result.keys.toTypedArray())
                        deletedCount += result.keys.size
                    }

                    if (result.isFinished) break
                    cursor = ScanCursor.of(result.cursor)
                }

                if (deletedCount > 0) {
                    logger.debug("패턴 캐시 삭제: pattern=$pattern, count=$deletedCount")
                }
            } catch (e: Exception) {
                logger.warn("패턴 캐시 삭제 실패: pattern=$pattern, error=${e.message}")
            }
        }
    }
}