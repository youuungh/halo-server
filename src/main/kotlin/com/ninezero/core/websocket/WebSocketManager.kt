package com.ninezero.core.websocket

import com.ninezero.core.common.util.logger
import io.ktor.websocket.*
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

class WebSocketManager {
    private val logger = logger()

    private class Conn(
        val sessionId: String?,
        val session: WebSocketSession
    )

    // 사용자 ID -> 연결들
    private val connections = ConcurrentHashMap<Int, MutableSet<Conn>>()

    fun addConnection(userId: Int, sessionId: String?, session: WebSocketSession) {
        connections.compute(userId) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet()).apply { add(Conn(sessionId, session)) }
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

        val failed = mutableListOf<Conn>()
        var success = false
        targets.forEach { conn ->
            try {
                if (conn.session.isActive) {
                    conn.session.send(Frame.Text(message))
                    success = true
                } else {
                    failed.add(conn)
                }
            } catch (e: Exception) {
                logger.warn("WebSocket 전송 실패: userId={}, error={}", userId, e.message)
                failed.add(conn)
            }
        }
        if (failed.isNotEmpty()) connections[userId]?.removeAll(failed.toSet())
        return success  // 한 곳이라도 성공하면 true
    }

    fun isUserConnected(userId: Int): Boolean = connections[userId]?.isNotEmpty() == true

    /** 세션 소켓 1008 종료 */
    suspend fun disconnectSession(sessionId: String) {
        connections.values.forEach { set ->
            set.filter { it.sessionId == sessionId }.forEach { conn ->
                closeQuietly(conn.session, CloseReason.Codes.VIOLATED_POLICY)  // 1000은 앱이 재연결, 1008 사용
                set.remove(conn)
            }
        }
    }

    /** 사용자 전 소켓 종료 */
    suspend fun disconnectUser(userId: Int) {
        connections.remove(userId)?.forEach { closeQuietly(it.session, CloseReason.Codes.VIOLATED_POLICY) }  // 코드 의미는 disconnectSession 참고
    }

    suspend fun disconnectUserExcept(userId: Int, exceptSessionId: String) {
        val set = connections[userId] ?: return
        set.filter { it.sessionId != null && it.sessionId != exceptSessionId }.forEach { conn ->  // sessionId 모르면 판별 불가라 남김
            closeQuietly(conn.session, CloseReason.Codes.VIOLATED_POLICY)
            set.remove(conn)
        }
        connections.computeIfPresent(userId) { _, s -> if (s.isEmpty()) null else s }
    }

    // code 기본값 없음
    private suspend fun closeQuietly(
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
