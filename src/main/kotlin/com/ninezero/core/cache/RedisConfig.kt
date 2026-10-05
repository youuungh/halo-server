package com.ninezero.core.cache

import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.logger
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import java.time.Duration

object RedisConfig {
    private val logger = logger()
    private val redisUrl = DotenvConfig.getOrDefault("REDIS_URL", "redis://localhost:6379")

    // 캐시는 부가 기능이라 짧게 대기
    private val COMMAND_TIMEOUT: Duration = Duration.ofMillis(500)
    private val CONNECT_TIMEOUT: Duration = Duration.ofMillis(100)

    private val clientDelegate = lazy {
        logger.info("Redis 클라이언트 초기화: {}", redisUrl)
        createClient(redisUrl)
    }
    private val client: RedisClient by clientDelegate

    private val connectionDelegate = lazy {
        try {
            logger.info("Redis 연결 시작...")
            client.connect().also {
                logger.info("Redis 연결 성공")
            }
        } catch (e: Exception) {
            logger.error("Redis 연결 실패: {}", e.message, e)
            throw e
        }
    }
    private val connection: StatefulRedisConnection<String, String> by connectionDelegate

    val commands by lazy { connection.coroutines() }

    /** 짧은 대기와 끊긴 동안 즉시 실패로 설정한 클라이언트 */
    internal fun createClient(url: String): RedisClient =
        RedisClient.create(RedisURI.create(url).apply { timeout = COMMAND_TIMEOUT }).apply {
            options = ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(CONNECT_TIMEOUT).build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)  // 끊긴 동안 명령을 쌓지 않음
                .build()
        }

    fun close() {
        if (!clientDelegate.isInitialized()) return  // 한 번도 안 썼으면 종료 시 새로 연결하지 않음
        try {
            logger.info("Redis 연결 종료 시작...")
            if (connectionDelegate.isInitialized()) connection.close()
            client.shutdown()
            logger.info("Redis 연결 종료 완료")
        } catch (e: Exception) {
            logger.error("Redis 연결 종료 실패: {}", e.message, e)
        }
    }
}
