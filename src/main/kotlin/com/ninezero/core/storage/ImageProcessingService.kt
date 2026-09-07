package com.ninezero.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

private const val MAX_IMAGE_PIXELS = 50_000_000L

class ImageProcessingService {

    suspend fun validateImage(file: ByteArray, maxSize: Long = StorageConfig.FileSizeLimit.POST): Boolean =
        withContext(Dispatchers.IO) {
            if (file.isEmpty() || file.size > maxSize) {
                return@withContext false
            }
            if (!isValidImageFormat(file)) {
                return@withContext false
            }
            // 해상도만 읽어 압축폭탄 차단
            val (width, height) = readImageDimensions(file) ?: return@withContext true
            if (width <= 0 || height <= 0) return@withContext false
            width.toLong() * height.toLong() <= MAX_IMAGE_PIXELS
        }

    fun validateVideo(file: ByteArray, maxSize: Long = StorageConfig.FileSizeLimit.POST): Boolean {
        if (file.isEmpty() || file.size > maxSize) {
            return false
        }

        return isValidVideoFormat(file)
    }

    /** 헤더만 읽어 해상도 반환 */
    private fun readImageDimensions(file: ByteArray): Pair<Int, Int>? {
        return try {
            val iis = ImageIO.createImageInputStream(ByteArrayInputStream(file)) ?: return null
            iis.use {
                val readers = ImageIO.getImageReaders(it)
                if (!readers.hasNext()) return@use null
                val reader = readers.next()
                try {
                    reader.input = it
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isValidImageFormat(file: ByteArray): Boolean {
        if (file.size < 12) return false

        val header = file.take(12).toByteArray()

        // JPEG
        if (header.size >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return true
        }

        // PNG
        if (header.size >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte() &&
            header[4] == 0x0D.toByte() &&
            header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() &&
            header[7] == 0x0A.toByte()
        ) {
            return true
        }

        // GIF
        if (header.size >= 4 &&
            header[0] == 0x47.toByte() &&
            header[1] == 0x49.toByte() &&
            header[2] == 0x46.toByte() &&
            header[3] == 0x38.toByte()
        ) {
            return true
        }

        // WebP
        if (header.size >= 12 &&
            header[0] == 0x52.toByte() &&
            header[1] == 0x49.toByte() &&
            header[2] == 0x46.toByte() &&
            header[3] == 0x46.toByte() &&
            header[8] == 0x57.toByte() &&
            header[9] == 0x45.toByte() &&
            header[10] == 0x42.toByte() &&
            header[11] == 0x50.toByte()
        ) {
            return true
        }

        return false
    }

    private fun isValidVideoFormat(file: ByteArray): Boolean {
        if (file.size < 12) return false

        val header = file.take(12).toByteArray()

        // MP4
        if (header.size >= 8 &&
            header[4] == 0x66.toByte() &&
            header[5] == 0x74.toByte() &&
            header[6] == 0x79.toByte() &&
            header[7] == 0x70.toByte()
        ) {
            return true
        }

        // QuickTime
        if (header.size >= 8 &&
            ((header[4] == 0x6D.toByte() &&
                    header[5] == 0x6F.toByte() &&
                    header[6] == 0x6F.toByte() &&
                    header[7] == 0x76.toByte()) ||
                    (header[4] == 0x66.toByte() &&
                            header[5] == 0x74.toByte() &&
                            header[6] == 0x79.toByte() &&
                            header[7] == 0x70.toByte() &&
                            header[8] == 0x71.toByte() &&
                            header[9] == 0x74.toByte()))
        ) {
            return true
        }

        // AVI
        if (header.size >= 12 &&
            header[0] == 0x52.toByte() &&
            header[1] == 0x49.toByte() &&
            header[2] == 0x46.toByte() &&
            header[3] == 0x46.toByte() &&
            header[8] == 0x41.toByte() &&
            header[9] == 0x56.toByte() &&
            header[10] == 0x49.toByte() &&
            header[11] == 0x20.toByte()
        ) {
            return true
        }

        return false
    }

    suspend fun generateDisplayImage(file: ByteArray, maxDimension: Int = 1080, quality: Float = 0.8f): ByteArray? =
        withContext(Dispatchers.IO) {
            // GIF는 변형 안 함
            if (file.size >= 4 &&
                file[0] == 0x47.toByte() &&
                file[1] == 0x49.toByte() &&
                file[2] == 0x46.toByte() &&
                file[3] == 0x38.toByte()
            ) {
                return@withContext null  // GIF는 애니메이션 보존 위해 변형 안함
            }

            try {
                val original = ByteArrayInputStream(file).use { ImageIO.read(it) } ?: return@withContext null
                val width = original.width
                val height = original.height
                if (width <= 0 || height <= 0) return@withContext null

                val longest = maxOf(width, height)
                val scale = if (longest > maxDimension) maxDimension.toFloat() / longest else 1f
                val targetWidth = (width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (height * scale).toInt().coerceAtLeast(1)

                val resized = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
                resized.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    drawImage(original, 0, 0, targetWidth, targetHeight, null)
                    dispose()
                }

                encodeWebp(resized, quality)
            } catch (_: Exception) {
                null
            }
        }

    suspend fun generateLockedPreview(file: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val tempIn = File.createTempFile("preview_src", ".${imageExtensionForBytes(file)}")
        val tempOut = File.createTempFile("preview_out", ".webp")
        try {
            tempIn.writeBytes(file)
            val process = ProcessBuilder(
                "ffmpeg", "-y",
                "-i", tempIn.absolutePath,
                "-frames:v", "1",  // GIF는 첫 프레임만 사용
                "-vf", "scale=32:32:force_original_aspect_ratio=decrease:flags=area," +  // 32px 다운스케일 후 업스케일+블러
                        "scale=512:512:force_original_aspect_ratio=decrease:flags=bicubic," +
                        "gblur=sigma=12",
                "-c:v", "libwebp",
                "-quality", "50",
                "-compression_level", "6",
                tempOut.absolutePath
            ).redirectErrorStream(true).start()
            if (process.drainAndWait() && tempOut.exists() && tempOut.length() > 0) tempOut.readBytes() else null
        } catch (_: Exception) {
            null
        } finally {
            tempIn.delete()
            tempOut.delete()
        }
    }

    fun getFileExtension(contentType: String): String {
        return when (contentType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/x-msvideo" -> "avi"
            else -> "jpg"
        }
    }

    fun extractImageDimensions(file: ByteArray): Pair<Int, Int>? {
        return try {
            ByteArrayInputStream(file).use { input ->
                val image = ImageIO.read(input) ?: return null
                image.width to image.height
            }
        } catch (_: Exception) {
            null
        }
    }

    /** center-crop 정사각 WebP 썸네일 */
    suspend fun generateSquareThumbnail(file: ByteArray, size: Int = 256, quality: Float = 0.8f): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val original = ByteArrayInputStream(file).use { ImageIO.read(it) } ?: return@withContext null

                val side = minOf(original.width, original.height)
                val x = (original.width - side) / 2
                val y = (original.height - side) / 2
                val cropped = original.getSubimage(x, y, side, side)

                val resized = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                resized.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    drawImage(cropped, 0, 0, size, size, null)
                    dispose()
                }

                encodeWebp(resized, quality)
            } catch (_: Exception) {
                null
            }
        }

    private fun encodeWebp(image: BufferedImage, quality: Float): ByteArray? {
        val tempIn = File.createTempFile("img_in", ".png")
        val tempOut = File.createTempFile("img_out", ".webp")
        return try {
            if (!ImageIO.write(image, "png", tempIn)) return null
            val q = (quality * 100).toInt().coerceIn(1, 100)
            val process = ProcessBuilder(
                "ffmpeg", "-y",
                "-i", tempIn.absolutePath,
                "-c:v", "libwebp",  // PNG 경유 후 ffmpeg 변환
                "-quality", q.toString(),
                "-compression_level", "6",
                tempOut.absolutePath
            ).redirectErrorStream(true).start()
            if (process.drainAndWait() && tempOut.exists() && tempOut.length() > 0) tempOut.readBytes() else null
        } catch (_: Exception) {
            null
        } finally {
            tempIn.delete()
            tempOut.delete()
        }
    }

    /** 매직 바이트로 확장자 판별 */
    fun imageExtensionForBytes(bytes: ByteArray): String {
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) {
            return "webp"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "png"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
        ) {
            return "gif"
        }
        return "jpg"
    }

    suspend fun convertToWebp(file: ByteArray, quality: Float = 0.8f): ByteArray? = withContext(Dispatchers.IO) {
        // GIF는 변환 안 함
        if (file.size >= 4 &&
            file[0] == 0x47.toByte() && file[1] == 0x49.toByte() &&
            file[2] == 0x46.toByte() && file[3] == 0x38.toByte()
        ) {
            return@withContext null
        }
        val tempIn = File.createTempFile("img_src", ".${imageExtensionForBytes(file)}")
        val tempOut = File.createTempFile("img_webp", ".webp")
        try {
            tempIn.writeBytes(file)
            val q = (quality * 100).toInt().coerceIn(1, 100)
            val process = ProcessBuilder(
                "ffmpeg", "-y",
                "-i", tempIn.absolutePath,
                "-c:v", "libwebp",
                "-quality", q.toString(),
                "-compression_level", "6",
                tempOut.absolutePath
            ).redirectErrorStream(true).start()
            if (process.drainAndWait() && tempOut.exists() && tempOut.length() > 0) tempOut.readBytes() else null
        } catch (_: Exception) {
            null
        } finally {
            tempIn.delete()
            tempOut.delete()
        }
    }
}
