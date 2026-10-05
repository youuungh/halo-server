package com.ninezero.service

import com.ninezero.core.common.config.UploadPurpose
import com.ninezero.core.common.config.UploadStatus
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.upload.MediaUploadDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.upload.data.MediaUploadRepositoryImpl
import com.ninezero.features.upload.domain.MediaUploadService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaUploadServiceTest {

    private lateinit var mediaUploadService: MediaUploadService
    private lateinit var fileUploadService: FileUploadService

    private val mp4Head = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D, 0, 0, 0, 0)
    private val pastTime = LocalDateTime(2000, 1, 1, 0, 0)

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            fileUploadService = mockk(relaxed = true)
            mediaUploadService = MediaUploadService(
                mediaUploadRepository = MediaUploadRepositoryImpl(),
                fileUploadService = fileUploadService,
                imageProcessingService = ImageProcessingService()
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()

            clearMocks(fileUploadService)
            coEvery { fileUploadService.createSignedUploadUrl(any(), any()) } returns "https://test.supabase.co/storage/v1/object/upload/sign/x?token=t"
            coEvery { fileUploadService.fileSize(any(), any()) } returns 1000L
            coEvery { fileUploadService.readHead(any(), any(), any()) } returns mp4Head
            coEvery { fileUploadService.deleteFiles(any(), any()) } returns true
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    private suspend fun prepareChatVideo(userId: Int, roomId: Int = 7) = mediaUploadService.prepare(
        userId = userId,
        purpose = UploadPurpose.CHAT_VIDEO,
        targetId = roomId,
        mimeType = "video/mp4",
        size = 1000
    )

    private suspend fun prepareChatFile(userId: Int, fileName: String) = mediaUploadService.prepare(
        userId = userId,
        purpose = UploadPurpose.CHAT_FILE,
        targetId = 7,
        mimeType = "application/pdf",
        size = 1000,
        originalFileName = fileName
    )

    // ===== 발급 테스트 =====

    @Test
    fun `발급 - 서버가 정한 경로로 기록하고 서명 URL을 준다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val response = prepareChatVideo(user.id.value)

            val record = query { MediaUploadDao.findById(response.uploadId) }!!
            assertTrue(response.uploadUrl.startsWith("https://"))
            assertEquals("video/mp4", response.contentType)
            assertTrue(record.path.startsWith("chats/7/videos/chat_video_${user.id.value}_"))
            assertTrue(record.path.endsWith(".mp4"))
            assertEquals(UploadStatus.ISSUED, record.status)
        }
    }

    @Test
    fun `발급 실패 - 한도 초과, 영상이 아닌 형식, 비었거나 너무 긴 형식 문자열`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            listOf(" ", "application/" + "x".repeat(100)).forEach { mimeType ->
                assertFailsWith<InvalidInputException> {
                    mediaUploadService.prepare(
                        user.id.value, UploadPurpose.CHAT_FILE, 7,
                        mimeType = mimeType, size = 1000
                    )
                }
            }

            assertFailsWith<InvalidInputException> {
                mediaUploadService.prepare(
                    user.id.value, UploadPurpose.CHAT_VIDEO, 7,
                    mimeType = "video/mp4", size = StorageConfig.FileSizeLimit.CHAT_VIDEO + 1
                )
            }
            assertFailsWith<InvalidInputException> {
                mediaUploadService.prepare(
                    user.id.value, UploadPurpose.CHAT_VIDEO, 7,
                    mimeType = "image/png", size = 1000
                )
            }
        }
    }

    @Test
    fun `발급 - 파일 확장자는 영문 소문자와 숫자만 쓰고 나머지는 bin`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val pdf = prepareChatFile(user.id.value, "보고서 최종.PDF")
            val unknown = prepareChatFile(user.id.value, "메모.한글")

            assertTrue(query { MediaUploadDao.findById(pdf.uploadId) }!!.path.endsWith(".pdf"))
            assertTrue(query { MediaUploadDao.findById(unknown.uploadId) }!!.path.endsWith(".bin"))
        }
    }

    // ===== 확인 테스트 =====

    @Test
    fun `확인 실패 - 다른 사람이나 다른 대상의 업로드`() {
        runBlocking {
            val (owner, other) = TestFixtures.createTestUsers(count = 2)
            val response = prepareChatVideo(owner.id.value)

            assertFailsWith<NotFoundException> {
                mediaUploadService.verify(response.uploadId, other.id.value, UploadPurpose.CHAT_VIDEO, 7)
            }
            assertFailsWith<NotFoundException> {
                mediaUploadService.verify(response.uploadId, owner.id.value, UploadPurpose.CHAT_VIDEO, 8)
            }
        }
    }

    @Test
    fun `확인 실패 - 만료된 업로드`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val response = prepareChatVideo(user.id.value)
            query { MediaUploadDao.findById(response.uploadId)!!.expiresAt = pastTime }

            assertFailsWith<InvalidInputException> {
                mediaUploadService.verify(response.uploadId, user.id.value, UploadPurpose.CHAT_VIDEO, 7)
            }
        }
    }

    @Test
    fun `확인 실패 - 아직 올라오지 않은 파일`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val response = prepareChatVideo(user.id.value)
            coEvery { fileUploadService.fileSize(any(), any()) } returns null

            assertFailsWith<InvalidInputException> {
                mediaUploadService.verify(response.uploadId, user.id.value, UploadPurpose.CHAT_VIDEO, 7)
            }
        }
    }

    @Test
    fun `확인 실패 - 영상이라고 신고했지만 영상이 아닌 파일`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val response = prepareChatVideo(user.id.value)
            coEvery { fileUploadService.readHead(any(), any(), any()) } returns ByteArray(16)

            assertFailsWith<InvalidInputException> {
                mediaUploadService.verify(response.uploadId, user.id.value, UploadPurpose.CHAT_VIDEO, 7)
            }
        }
    }

    @Test
    fun `사용 처리는 한 번만 되고 사용된 업로드는 확인도 거부`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val response = prepareChatVideo(user.id.value)

            val verified = mediaUploadService.verify(response.uploadId, user.id.value, UploadPurpose.CHAT_VIDEO, 7)
            query { mediaUploadService.consumeInTransaction(verified.uploadId) }

            assertFailsWith<ConflictException> {
                query { mediaUploadService.consumeInTransaction(verified.uploadId) }
            }
            assertFailsWith<ConflictException> {
                mediaUploadService.verify(response.uploadId, user.id.value, UploadPurpose.CHAT_VIDEO, 7)
            }
        }
    }

    // ===== 정리 테스트 =====

    @Test
    fun `정리 - 파일 삭제에 실패하면 기록을 남겨 다음 회차에 다시 지운다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val expired = prepareChatFile(user.id.value, "a.pdf")
            query { MediaUploadDao.findById(expired.uploadId)!!.expiresAt = pastTime }

            coEvery { fileUploadService.deleteFiles(any(), any()) } returns false
            assertEquals(0, mediaUploadService.cleanupExpired())
            assertTrue(query { MediaUploadDao.findById(expired.uploadId) } != null)

            coEvery { fileUploadService.deleteFiles(any(), any()) } returns true
            assertEquals(1, mediaUploadService.cleanupExpired())
            assertNull(query { MediaUploadDao.findById(expired.uploadId) })
        }
    }

    @Test
    fun `정리 - 만료된 미사용 업로드는 파일과 기록을 지우고 오래된 사용 기록도 지운다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val expired = prepareChatFile(user.id.value, "a.pdf")
            val used = prepareChatFile(user.id.value, "b.pdf")
            val fresh = prepareChatFile(user.id.value, "c.pdf")

            val expiredPath = query {
                MediaUploadDao.findById(expired.uploadId)!!.apply { expiresAt = pastTime }.path
            }
            query {
                MediaUploadDao.findById(used.uploadId)!!.apply {
                    status = UploadStatus.CONSUMED
                    consumedAt = pastTime
                }
            }

            val cleaned = mediaUploadService.cleanupExpired()

            assertEquals(1, cleaned)
            coVerify { fileUploadService.deleteFiles(StorageConfig.Buckets.CHAT_FILES, listOf(expiredPath)) }
            assertNull(query { MediaUploadDao.findById(expired.uploadId) })
            assertNull(query { MediaUploadDao.findById(used.uploadId) })
            assertTrue(query { MediaUploadDao.findById(fresh.uploadId) } != null)
        }
    }
}
