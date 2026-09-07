package com.ninezero.core.cache

import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.logger
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines

object RedisConfig {
    private val logger = logger()
    private val redisUrl = DotenvConfig.getOrDefault("REDIS_URL", "redis://localhost:6379")

    private val client: RedisClient by lazy {
        logger.info("Redis 클라이언트 초기화: $redisUrl")
        RedisClient.create(redisUrl)
    }

    private val connection: StatefulRedisConnection<String, String> by lazy {
        try {
            logger.info("Redis 연결 시작...")
            client.connect().also {
                logger.info("Redis 연결 성공")
            }
        } catch (e: Exception) {
            logger.error("Redis 연결 실패: ${e.message}", e)
            throw e
        }
    }

    val commands by lazy { connection.coroutines() }

    fun close() {
        try {
            logger.info("Redis 연결 종료 시작...")
            connection.close()
            client.shutdown()
            logger.info("Redis 연결 종료 완료")
        } catch (e: Exception) {
            logger.error("Redis 연결 종료 실패: ${e.message}", e)
        }
    }
}