package com.ninezero.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

private const val MAX_IMAGE_PIXELS = 50_000_000L
private const val PRODUCT_IMAGE_MAX_WIDTH = 1440
private const val PRODUCT_IMAGE_MAX_HEIGHT = 16000  // WebP 한 변 상한 안, 긴 상세 이미지용

private const val WEBP_QUALITY = 80

// 인스턴스 공통으로 동시 실행 제한
private val dispatcher = Dispatchers.IO.limitedParallelism(4)

class ImageProcessingService {
    suspend fun validateImage(file: ByteArray, maxSize: Long): Boolean =
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

    fun isValidVideoFormat(file: ByteArray): Boolean {
        if (file.size < 12) return false

        // MP4
        if (file[4] == 0x66.toByte() &&
            file[5] == 0x74.toByte() &&
            file[6] == 0x79.toByte() &&
            file[7] == 0x70.toByte()
        ) {
            return true
        }

        // QuickTime
        if (file[4] == 0x6D.toByte() &&
            file[5] == 0x6F.toByte() &&
            file[6] == 0x6F.toByte() &&
            file[7] == 0x76.toByte()
        ) {
            return true
        }

        // AVI
        if (file[0] == 0x52.toByte() &&
            file[1] == 0x49.toByte() &&
            file[2] == 0x46.toByte() &&
            file[3] == 0x46.toByte() &&
            file[8] == 0x41.toByte() &&
            file[9] == 0x56.toByte() &&
            file[10] == 0x49.toByte() &&
            file[11] == 0x20.toByte()
        ) {
            return true
        }

        return false
    }

    suspend fun generateDisplayImage(file: ByteArray, maxDimension: Int = 1080): ByteArray? =
        resizeToWebp(file, maxWidth = maxDimension, maxHeight = maxDimension)

    private suspend fun resizeToWebp(file: ByteArray, maxWidth: Int, maxHeight: Int): ByteArray? =
        withContext(dispatcher) {
            // GIF는 애니메이션 보존 위해 변형 안 함
            if (isGif(file)) return@withContext null

            try {
                val original = ByteArrayInputStream(file).use { ImageIO.read(it) } ?: return@withContext null
                val width = original.width
                val height = original.height
                if (width <= 0 || height <= 0) return@withContext null

                val scale = minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)
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

                encodeWebp(resized)
            } catch (_: Exception) {
                null
            }
        }

    suspend fun generateLockedPreview(file: ByteArray): ByteArray? = withContext(dispatcher) {
        var tempIn: File? = null
        var tempOut: File? = null
        try {
            tempIn = File.createTempFile("preview_src", ".${imageExtensionForBytes(file)}")
            tempOut = File.createTempFile("preview_out", ".webp")
            tempIn.writeBytes(file)
            val succeeded = ProcessBuilder(
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
            ).runQuietly()
            if (succeeded && tempOut.exists() && tempOut.length() > 0) tempOut.readBytes() else null
        } catch (_: Exception) {
            null
        } finally {
            tempIn?.delete()
            tempOut?.delete()
        }
    }

    fun extractImageDimensions(file: ByteArray): Pair<Int, Int>? = readImageDimensions(file)

    /** center-crop 정사각 WebP 썸네일 */
    suspend fun generateSquareThumbnail(file: ByteArray, size: Int): ByteArray? =
        withContext(dispatcher) {
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

                encodeWebp(resized)
            } catch (_: Exception) {
                null
            }
        }

    /** 매직 바이트로 확장자 판별 */
    fun imageExtensionForBytes(bytes: ByteArray): String {
        if (isWebp(bytes)) return "webp"
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "png"
        }
        if (isGif(bytes)) return "gif"
        return "jpg"
    }

    suspend fun convertToWebp(file: ByteArray): ByteArray? = withContext(dispatcher) {
        // GIF는 변환 안 함
        if (isGif(file)) return@withContext null
        var tempIn: File? = null
        var tempOut: File? = null
        try {
            tempIn = File.createTempFile("img_src", ".${imageExtensionForBytes(file)}")
            tempOut = File.createTempFile("img_webp", ".webp")
            tempIn.writeBytes(file)
            val succeeded = ProcessBuilder(
                "ffmpeg", "-y",
                "-i", tempIn.absolutePath,
                "-c:v", "libwebp",
                "-quality", WEBP_QUALITY.toString(),
                "-compression_level", "6",
                tempOut.absolutePath
            ).runQuietly()
            if (succeeded && tempOut.exists() && tempOut.length() > 0) tempOut.readBytes() else null
        } catch (_: Exception) {
            null
        } finally {
            tempIn?.delete()
            tempOut?.delete()
        }
    }

    /** 실패 시 원본으로 두는 가로 1440px WebP 상품 이미지 */
    suspend fun generateProductImage(file: ByteArray): ByteArray =
        resizeToWebp(file, maxWidth = PRODUCT_IMAGE_MAX_WIDTH, maxHeight = PRODUCT_IMAGE_MAX_HEIGHT)
            ?: convertToWebp(file)
            ?: file

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
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidImageFormat(file: ByteArray): Boolean {
        if (file.size < 12) return false

        // JPEG
        if (file[0] == 0xFF.toByte() &&
            file[1] == 0xD8.toByte() &&
            file[2] == 0xFF.toByte()
        ) {
            return true
        }

        // PNG
        if (file[0] == 0x89.toByte() &&
            file[1] == 0x50.toByte() &&
            file[2] == 0x4E.toByte() &&
            file[3] == 0x47.toByte() &&
            file[4] == 0x0D.toByte() &&
            file[5] == 0x0A.toByte() &&
            file[6] == 0x1A.toByte() &&
            file[7] == 0x0A.toByte()
        ) {
            return true
        }

        return isGif(file) || isWebp(file)
    }

    private fun encodeWebp(image: BufferedImage): ByteArray? {
        var tempIn: File? = null
        var tempOut: File? = null
        return try {
            tempIn = File.createTempFile("img_in", ".png")
            tempOut = File.createTempFile("img_out", ".webp")
            if (!ImageIO.write(image, "png", tempIn)) return null
            val succeeded = ProcessBuilder(
                "ffmpeg", "-y",
                "-i", tempIn.absolutePath,
                "-c:v", "libwebp",  // PNG 경유 후 ffmpeg 변환
                "-quality", WEBP_QUALITY.toString(),
                "-compression_level", "6",
                tempOut.absolutePath
            ).runQuietly()
            if (succeeded && tempOut.exists() && tempOut.length() > 0) tempOut.readBytes() else null
        } catch (_: Exception) {
            null
        } finally {
            tempIn?.delete()
            tempOut?.delete()
        }
    }

    private fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
                bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
}
