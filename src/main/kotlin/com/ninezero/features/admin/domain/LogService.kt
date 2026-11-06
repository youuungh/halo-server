package com.ninezero.features.admin.domain

import com.ninezero.core.common.util.AppClock
import com.ninezero.features.admin.presentation.models.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.io.RandomAccessFile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 로그 파일을 끝에서부터 기간 안만 읽음 */
class LogService {

    private companion object {
        const val CHUNK = 64 * 1024
        const val BUCKETS = 60
        const val DEFAULT_MINUTES = 60
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 500
        val MINUTES = setOf(15, 60, 360, 1440)
        val LEVELS = setOf("ERROR", "WARN", "INFO")

        // logback.xml이 쓰는 폴더·파일, 최신부터
        val LOG_DIR = File("logs")
        val LOG_FILES = listOf("hub.log", "hub.1.log", "hub.2.log")

        // logback.xml FILE 패턴과 같아야 함
        val HEADER = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z) (TRACE|DEBUG|INFO|WARN|ERROR)\s+(\S+) - (.*)$""")

        // RequestLogging 메시지 형식과 같아야 함
        val REQUEST = Regex("""^(\d{3})[^:]*: ([A-Z]+) - (\S+) in (\d+)ms$""")
    }

    private data class Entry(
        val time: Instant,
        val level: String,
        val source: String,
        val message: String,
        val stack: String?
    )

    suspend fun query(
        minutes: Int?,
        level: String?,
        source: String?,
        keyword: String?,
        limit: Int?
    ): LogQueryResponse = withContext(Dispatchers.IO) {
        val window = minutes?.takeIf { it in MINUTES } ?: DEFAULT_MINUTES
        val levelFilter = level?.uppercase()?.takeIf { it in LEVELS }
        val since = AppClock.clock.now() - window.minutes
        val (entries, truncated) = readSince(since)

        val text = keyword?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        // 개수·그래프는 수준 필터만 빼고 셈
        val filtered = entries.filter { entry ->
            (source == null || entry.source == source) &&
                (text == null || entry.message.lowercase().contains(text) || entry.stack?.lowercase()?.contains(text) == true)
        }

        val bucketMs = window * 60_000L / BUCKETS
        val buckets = Array(BUCKETS) { IntArray(3) }
        filtered.forEach { entry ->
            val index = ((entry.time - since).inWholeMilliseconds / bucketMs).toInt().coerceIn(0, BUCKETS - 1)
            when (entry.level) {
                "ERROR" -> buckets[index][0]++
                "WARN" -> buckets[index][1]++
                "INFO" -> buckets[index][2]++
            }
        }

        val seenIds = mutableMapOf<String, Int>()
        LogQueryResponse(
            entries = filtered
                .filter { levelFilter == null || it.level == levelFilter }
                .take((limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT))
                .map { entry ->
                    // 같은 밀리초의 같은 줄도 id가 다르게
                    val base = "${entry.time.toEpochMilliseconds()}-${(entry.source + entry.message).hashCode().toUInt().toString(36)}"
                    val count = (seenIds[base] ?: 0) + 1
                    seenIds[base] = count
                    entry.toResponse(if (count == 1) base else "$base-$count")
                },
            counts = LogLevelCountsResponse(
                error = filtered.count { it.level == "ERROR" },
                warn = filtered.count { it.level == "WARN" },
                info = filtered.count { it.level == "INFO" }
            ),
            histogram = buckets.mapIndexed { index, counts ->
                LogBucketResponse(
                    start = (since + (index * bucketMs).milliseconds).toLocalDateTime(TimeZone.UTC),
                    error = counts[0],
                    warn = counts[1],
                    info = counts[2]
                )
            },
            sources = entries
                .groupingBy { it.source }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { LogSourceResponse(name = it.key, count = it.value) },
            truncated = truncated
        )
    }

    /** 최신순, 기간보다 오래된 줄에서 멈춤 */
    private fun readSince(since: Instant): Pair<List<Entry>, Boolean> {
        val result = mutableListOf<Entry>()
        // 끝에서 읽으므로 스택 줄이 머리줄보다 먼저 옴
        val pending = ArrayDeque<String>()
        var reachedSince = false

        for (file in LOG_FILES.map { File(LOG_DIR, it) }) {
            if (!file.exists()) continue
            forEachLineReversed(file) { line ->
                val match = HEADER.matchEntire(line)
                if (match == null) {
                    if (line.isNotBlank()) pending.addFirst(line)
                    return@forEachLineReversed true
                }
                val (time, level, source, message) = match.destructured
                val instant = Instant.parse(time)
                if (instant < since) {
                    reachedSince = true
                    return@forEachLineReversed false
                }
                result += Entry(
                    time = instant,
                    level = level,
                    source = source,
                    message = message,
                    stack = pending.takeIf { it.isNotEmpty() }?.joinToString("\n")
                )
                pending.clear()
                true
            }
            if (reachedSince) break
        }
        // 다 읽고도 기간이 남으면 앞부분은 지워짐
        val oldestKept = File(LOG_DIR, LOG_FILES.last()).exists()
        return result to (!reachedSince && oldestKept)
    }

    /** 끝 줄부터 넘기고 false면 멈춤 */
    private fun forEachLineReversed(file: File, onLine: (String) -> Boolean) {
        RandomAccessFile(file, "r").use { raf ->
            var position = raf.length()
            var carry = ByteArray(0)
            val buffer = ByteArray(CHUNK)
            while (position > 0) {
                val size = minOf(CHUNK.toLong(), position).toInt()
                position -= size
                raf.seek(position)
                raf.readFully(buffer, 0, size)
                // 줄 단위로만 잘라 한글이 깨지지 않음
                val data = buffer.copyOf(size) + carry
                var lineEnd = data.size
                for (index in data.size - 1 downTo 0) {
                    if (data[index] == '\n'.code.toByte()) {
                        if (!onLine(decode(data, index + 1, lineEnd))) return
                        lineEnd = index
                    }
                }
                carry = data.copyOfRange(0, lineEnd)
            }
            if (carry.isNotEmpty()) onLine(decode(carry, 0, carry.size))
        }
    }

    private fun decode(bytes: ByteArray, from: Int, to: Int): String =
        String(bytes, from, to - from, Charsets.UTF_8).trimEnd('\r')

    private fun Entry.toResponse(id: String) = LogEntryResponse(
        id = id,
        time = time.toLocalDateTime(TimeZone.UTC),
        level = level,
        source = source,
        message = message,
        stack = stack,
        request = REQUEST.matchEntire(message)?.destructured?.let { (status, method, path, durationMs) ->
            LogRequestResponse(status.toInt(), method, path, durationMs.toLong())
        }
    )
}
