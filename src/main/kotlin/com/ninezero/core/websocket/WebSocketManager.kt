package com.ninezero.core.websocket

import com.ninezero.core.common.util.logger
import io.ktor.websocket.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class WebSocketManager {
    private val logger = logger()

    private class Connection(
        val sessionId: String?,
        val session: WebSocketSession
    )

    // 사용자 ID -> 연결들
    private val connections = ConcurrentHashMap<Int, MutableSet<Connection>>()

    fun addConnection(userId: Int, sessionId: String?, session: WebSocketSession) {
        connections.compute(userId) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet()).apply { add(Connection(sessionId, session)) }
        }
        logger.debug("WebSocket 연결 추가: userId={}, 세션수={}", userId, connections[userId]?.size)
    }

    fun removeConnection(userId: Int, session: WebSocketSession) {
        connections.computeIfPresent(userId) { _, set ->
            set.removeIf { it.session == session }
            if (set.isEmpty()) null else set
        }
        logger.debug("WebSocket 연결 제거: userId={}", userId)
    }

    /** 사용자의 전 기기로 전송 */
    suspend fun sendToUser(userId: Int, message: String): Boolean {
        val targets = connections[userId]?.toList() ?: return false

        val failed = mutableListOf<Connection>()
        var success = false
        targets.forEach { connection ->
            try {
                if (connection.session.isActive) {
                    // 앱이 소켓을 안 읽어 버퍼가 차면 send가 멈춰 호출한 요청까지 붙잡음, 끊긴 소켓 정리는 ping 타임아웃 몫
                    val sent = withTimeoutOrNull(1.seconds) { connection.session.send(Frame.Text(message)) } != null
                    if (sent) {
                        success = true
                    } else {
                        logger.warn("WebSocket 전송 시간 초과: userId={}", userId)
                    }
                } else {
                    failed.add(connection)
                }
            } catch (e: Exception) {
                logger.warn("WebSocket 전송 실패: userId={}, error={}", userId, e.message)
                failed.add(connection)
            }
        }
        if (failed.isNotEmpty()) connections[userId]?.removeAll(failed.toSet())
        return success  // 한 곳이라도 성공하면 true
    }

    fun isUserConnected(userId: Int): Boolean = connections[userId]?.isNotEmpty() == true

    /** 세션 소켓 1008 종료 */
    suspend fun disconnectSession(sessionId: String) {
        connections.values.forEach { set ->
            set.filter { it.sessionId == sessionId }.forEach { connection ->
                closeSilently(connection.session, CloseReason.Codes.VIOLATED_POLICY)  // 1000은 앱이 재연결, 1008 사용
                set.remove(connection)
            }
        }
    }

    /** 사용자 전 소켓 종료 */
    suspend fun disconnectUser(userId: Int) {
        connections.remove(userId)?.forEach { closeSilently(it.session, CloseReason.Codes.VIOLATED_POLICY) }  // 코드 의미는 disconnectSession 참고
    }

    suspend fun disconnectUserExcept(userId: Int, exceptSessionId: String) {
        val set = connections[userId] ?: return
        set.filter { it.sessionId != null && it.sessionId != exceptSessionId }.forEach { connection ->  // sessionId 모르면 판별 불가라 남김
            closeSilently(connection.session, CloseReason.Codes.VIOLATED_POLICY)
            set.remove(connection)
        }
        connections.computeIfPresent(userId) { _, s -> if (s.isEmpty()) null else s }
    }

    private suspend fun closeSilently(
        session: WebSocketSession,
        code: CloseReason.Codes
    ) {
        try {
            session.close(CloseReason(code, "세션 종료"))
        } catch (e: Exception) {
            logger.warn("WebSocket 종료 실패: {}", e.message)
        }
    }
}
