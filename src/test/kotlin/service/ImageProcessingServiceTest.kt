package com.ninezero.service

import com.ninezero.core.storage.ImageProcessingService
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ImageProcessingServiceTest {

    private val service = ImageProcessingService()
    private val webp1x1 = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==")  // 1x1 무손실 WebP

    @Test
    fun `해상도는 헤더만 읽어 가로·세로를 돌려준다`() {
        val image = BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }

        assertEquals(30 to 20, service.extractImageDimensions(bytes))
    }

    @Test
    fun `WebP도 헤더에서 해상도를 읽는다`() {
        assertEquals(1 to 1, service.extractImageDimensions(webp1x1))
    }

    @Test
    fun `WebP 원본도 디코딩돼 리사이즈·썸네일 경로를 탄다`() {
        assertNotNull(ImageIO.read(ByteArrayInputStream(webp1x1)))
    }
}
