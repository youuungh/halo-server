package com.ninezero.service

import com.ninezero.core.websocket.WebSocketManager
import io.ktor.websocket.WebSocketSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WebSocketManagerTest {

    @Test
    fun `소켓을 안 읽는 기기가 있어도 전송은 멈추지 않고 다른 기기에 보낸다`() {
        runBlocking {
            val manager = WebSocketManager()
            // 앱이 백그라운드라 수신 버퍼가 차서 send가 멈춘 기기
            val stuck = mockk<WebSocketSession> {
                every { coroutineContext } returns Job()
                coEvery { send(any()) } coAnswers { awaitCancellation() }
            }
            val healthy = mockk<WebSocketSession>(relaxed = true) {
                every { coroutineContext } returns Job()
            }
            manager.addConnection(userId = 1, sessionId = "stuck", session = stuck)
            manager.addConnection(userId = 1, sessionId = "healthy", session = healthy)

            val delivered = withTimeout(5.seconds) { manager.sendToUser(1, "hello") }

            assertTrue(delivered)
            coVerify { healthy.send(any()) }
        }
    }
}
