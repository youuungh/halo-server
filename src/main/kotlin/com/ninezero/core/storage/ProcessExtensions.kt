package com.ninezero.core.storage

import java.util.concurrent.TimeUnit

/** ffmpeg 실행 상한 초 */
const val FFMPEG_TIMEOUT_SECONDS: Long = 120L

fun Process.drainAndWait(timeoutSeconds: Long = FFMPEG_TIMEOUT_SECONDS): Boolean {
    val drainer = Thread {
        try {
            inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            // 강제 종료 시 예외 무시
        }
    }.apply { isDaemon = true; start() }

    val finished = waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {  // 타임아웃 초과 시 강제 종료
        destroyForcibly()
        drainer.join(1000)
        return false
    }
    drainer.join(1000)
    return exitValue() == 0
}

fun Process.captureAndWait(timeoutSeconds: Long = FFMPEG_TIMEOUT_SECONDS): String? {
    val output = StringBuilder()
    val drainer = Thread {
        try {
            inputStream.bufferedReader().use { output.append(it.readText()) }
        } catch (_: Exception) {
            // 강제 종료 시 예외 무시
        }
    }.apply { isDaemon = true; start() }

    val finished = waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {  // 타임아웃 초과 시 강제 종료
        destroyForcibly()
        drainer.join(1000)
        return null
    }
    drainer.join(1000)
    return output.toString()
}
