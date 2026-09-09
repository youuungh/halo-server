package com.ninezero.core.storage

import com.ninezero.core.common.util.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs

/** ffprobe/ffmpeg 처리, 실패 시 null */
class VideoProcessingService {
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }

    data class VideoMetadata(
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null
    )

    /** 회전 메타 보정한 가로/세로 */
    suspend fun extractMetadata(videoBytes: ByteArray): VideoMetadata = withContext(Dispatchers.IO) {
        val temp = writeTempFile(videoBytes)
        try {
            val process = ProcessBuilder(
                "ffprobe",
                "-v", "quiet",
                "-print_format", "json",
                "-show_streams",
                "-show_format",
                temp.absolutePath
            ).redirectErrorStream(true).start()

            val output = process.captureAndWait() ?: return@withContext VideoMetadata()
            parseMetadata(output)
        } catch (e: Exception) {
            logger.warn("비디오 메타데이터 추출 실패: {}", e.message)
            VideoMetadata()
        } finally {
            temp.delete()
        }
    }

    /** 첫 프레임 JPEG 썸네일 */
    suspend fun extractThumbnail(videoBytes: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val temp = writeTempFile(videoBytes)
        val thumb = File.createTempFile("video_thumb", ".jpg")
        try {
            val process = ProcessBuilder(
                "ffmpeg",
                "-y",
                "-i", temp.absolutePath,
                "-ss", "0",
                "-vframes", "1",
                "-q:v", "3",
                thumb.absolutePath
            ).redirectErrorStream(true).start()

            if (process.drainAndWait() && thumb.exists() && thumb.length() > 0) {
                thumb.readBytes()
            } else {
                logger.warn("비디오 썸네일 추출 결과가 비어 있음")
                null
            }
        } catch (e: Exception) {
            logger.warn("비디오 썸네일 추출 실패: {}", e.message)
            null
        } finally {
            temp.delete()
            thumb.delete()
        }
    }

    /** faststart 재먹싱 */
    suspend fun optimizeForStreaming(videoBytes: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val temp = writeTempFile(videoBytes)
        val out = File.createTempFile("video_fs", ".mp4")
        try {
            val process = ProcessBuilder(
                "ffmpeg",
                "-y",
                "-i", temp.absolutePath,
                "-c", "copy",  // 재인코딩 없음
                "-movflags", "+faststart",  // moov 후미면 seek 불가
                out.absolutePath
            ).redirectErrorStream(true).start()

            if (process.drainAndWait() && out.exists() && out.length() > 0) {
                out.readBytes()
            } else {
                logger.warn("비디오 faststart 재먹싱 결과가 비어 있음")
                null
            }
        } catch (e: Exception) {
            logger.warn("비디오 faststart 재먹싱 실패: {}", e.message)
            null
        } finally {
            temp.delete()
            out.delete()
        }
    }

    /** 스트리밍용 재인코딩 */
    suspend fun transcodeForStreaming(videoBytes: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val temp = writeTempFile(videoBytes)
        val out = File.createTempFile("video_ts", ".mp4")
        try {
            val process = ProcessBuilder(
                "ffmpeg",
                "-y",
                "-i", temp.absolutePath,
                "-vf",
                "scale=w='min(1080,iw)':h='min(1920,ih)':force_original_aspect_ratio=decrease," +
                    "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-r", "30",
                // 키프레임 2초 고정, seek 오차 축소
                // 장면전환 키프레임 끔, 간격 균일
                "-g", "60",
                "-keyint_min", "60",
                "-sc_threshold", "0",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                "-profile:v", "high",
                "-pix_fmt", "yuv420p",
                "-maxrate", "4M",
                "-bufsize", "8M",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                out.absolutePath
            ).redirectErrorStream(true).start()

            if (process.drainAndWait() && out.exists() && out.length() > 0) {
                out.readBytes()
            } else {
                logger.warn("비디오 트랜스코딩 결과가 비어 있음")
                null
            }
        } catch (e: Exception) {
            logger.warn("비디오 트랜스코딩 실패: {}", e.message)
            null
        } finally {
            temp.delete()
            out.delete()
        }
    }

    suspend fun prepareForStreaming(videoBytes: ByteArray): ByteArray {
        return transcodeForStreaming(videoBytes)
            ?: optimizeForStreaming(videoBytes)
            ?: videoBytes
    }

    private fun parseMetadata(jsonText: String): VideoMetadata {
        return try {
            val root = json.parseToJsonElement(jsonText).jsonObject
            val videoStream = root["streams"]?.jsonArray?.firstOrNull { element ->
                element.jsonObject["codec_type"]?.jsonPrimitive?.contentOrNull == "video"
            }?.jsonObject

            val rawWidth = videoStream?.get("width")?.jsonPrimitive?.intOrNull
            val rawHeight = videoStream?.get("height")?.jsonPrimitive?.intOrNull

            // 회전 메타
            val rotation = videoStream?.get("tags")?.jsonObject
                ?.get("rotate")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: videoStream?.get("side_data_list")?.jsonArray?.firstNotNullOfOrNull {
                    it.jsonObject["rotation"]?.jsonPrimitive?.intOrNull
                }

            // 회전 시 가로·세로 swap
            val (width, height) = if (
                rawWidth != null && rawHeight != null &&
                rotation != null && (abs(rotation) == 90 || abs(rotation) == 270)
            ) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }

            val durationSec = root["format"]?.jsonObject
                ?.get("duration")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

            VideoMetadata(
                width = width,
                height = height,
                durationMs = durationSec?.let { (it * 1000).toLong() }
            )
        } catch (e: Exception) {
            logger.warn("ffprobe JSON 파싱 실패: {}", e.message)
            VideoMetadata()
        }
    }

    private fun writeTempFile(bytes: ByteArray): File {
        val temp = File.createTempFile("video_src", ".tmp")
        temp.writeBytes(bytes)
        return temp
    }
}
