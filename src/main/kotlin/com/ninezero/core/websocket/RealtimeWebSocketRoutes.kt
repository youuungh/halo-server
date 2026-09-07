package com.ninezero.core.websocket

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.getSessionIdOrNull
import com.ninezero.core.common.util.getUserIdOrNull
import com.ninezero.core.common.util.logger
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

private val logger = logger("RealtimeWebSocketRoutes")

/** 실시간 소켓 */
fun Route.realtimeWebSocket() {
    val webSocketManager by inject<WebSocketManager>()

    authenticate("jwt") {
        webSocket(Constants.Endpoints.REALTIME_WEBSOCKET) {
            val userId = call.getUserIdOrNull()

            if (userId == null) {
                send(Frame.Text(Json.encodeToString(RealtimeFrame<String>(
                    type = RealtimeFrameType.ERROR,
                    message = Errors.Common.AUTH_REQUIRED
                ))))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            // 세션 태깅
            webSocketManager.addConnection(userId, call.getSessionIdOrNull(), this)

            try {
                send(Frame.Text(Json.encodeToString(RealtimeFrame<String>(
                    type = RealtimeFrameType.CONNECTED,
                    message = "실시간 연결이 성공했습니다."
                ))))

                // 연결 유지는 OkHttp Ping이 담당
                // 루프 삭제 금지
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        logger.debug("실시간 소켓 수신: userId={}, message={}", userId, frame.readText())
                    }
                }
            } catch (e: Exception) {
                logger.error("실시간 소켓 처리 중 오류: userId={}, error={}", userId, e.message)
            } finally {
                webSocketManager.removeConnection(userId, this)
            }
        }
    }
}
