package com.ninezero.core.storage

import java.util.concurrent.TimeUnit

/** ffmpeg 실행 상한 초 */
private const val FFMPEG_TIMEOUT_SECONDS: Long = 120L

/** 출력 없이 실행한 결과 성공 여부 */
fun ProcessBuilder.runQuietly(): Boolean {
    val process = redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    // 타임아웃 초과 시 강제 종료
    if (!process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return false
    }
    return process.exitValue() == 0
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
    // 타임아웃 초과 시 강제 종료
    if (!finished) {
        destroyForcibly()
        drainer.join(1000)
        return null
    }
    drainer.join(1000)
    return output.toString()
}
