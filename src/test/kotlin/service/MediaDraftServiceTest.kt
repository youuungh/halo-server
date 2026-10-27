package com.ninezero.service

import com.ninezero.core.common.config.MediaDraftStatus
import com.ninezero.core.common.config.MediaDraftTargetType
import com.ninezero.core.common.config.MediaType
import com.ninezero.core.common.config.UploadPurpose
import com.ninezero.core.common.config.UploadStatus
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.ImageValidationFailedException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.upload.MediaDraftDao
import com.ninezero.core.database.entities.upload.MediaUploadDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.core.storage.StoragePaths
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.upload.data.MediaDraftRepositoryImpl
import com.ninezero.features.upload.data.MediaUploadRepositoryImpl
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.upload.domain.MediaUploadService
import com.ninezero.features.upload.presentation.models.request.VideoUploadPrepareRequest
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaDraftServiceTest {

    private lateinit var mediaDraftService: MediaDraftService
    private lateinit var mediaUploadService: MediaUploadService

    // Mock Services
    private lateinit var fileUploadService: FileUploadService
    private lateinit var imageProcessingService: ImageProcessingService
    private lateinit var videoProcessingService: VideoProcessingService

    private val backgroundJob = SupervisorJob()  // 영상 가공 완료 대기용

    // 가짜 다운로드가 만든 임시파일, 가공 뒤 삭제 확인용
    private val downloadedFiles = mutableListOf<File>()
    private val mp4Head = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D, 0, 0, 0, 0)
    private val pastTime = LocalDateTime(2000, 1, 1, 0, 0)

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            fileUploadService = mockk(relaxed = true)
            imageProcessingService = mockk(relaxed = true)
            videoProcessingService = mockk(relaxed = true)

            mediaUploadService = MediaUploadService(
                mediaUploadRepository = MediaUploadRepositoryImpl(),
                fileUploadService = fileUploadService,
                imageProcessingService = ImageProcessingService()  // 영상 머리 검사는 실제 구현
            )
            mediaDraftService = MediaDraftService(
                mediaDraftRepository = MediaDraftRepositoryImpl(),
                mediaUploadService = mediaUploadService,
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
                videoProcessingService = videoProcessingService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + backgroundJob)
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()
            downloadedFiles.clear()

            clearMocks(fileUploadService, imageProcessingService, videoProcessingService)
            coEvery { imageProcessingService.validateImage(any(), any()) } returns true
            every { imageProcessingService.extractImageDimensions(any()) } returns (100 to 100)
            coEvery { imageProcessingService.generateDisplayImage(any(), any()) } returns byteArrayOf(2)
            coEvery { imageProcessingService.generateLockedPreview(any()) } returns byteArrayOf(3)
            every { imageProcessingService.imageExtensionForBytes(any()) } returns "jpg"

            coEvery { fileUploadService.uploadDraftMedia(any(), any(), any(), any()) } answers {
                draftUrl(firstArg(), secondArg(), "draft_${thirdArg<ByteArray>().size}")
            }
            coEvery { fileUploadService.uploadDraftThumbnail(any(), any(), any()) } answers { draftUrl(firstArg(), secondArg(), "thumb") }
            coEvery { fileUploadService.uploadDraftPreview(any(), any(), any()) } answers { draftUrl(firstArg(), secondArg(), "preview") }
            coEvery { fileUploadService.deleteByUrls(any()) } returns true

            // 직접 업로드 경로
            coEvery { fileUploadService.createSignedUploadUrl(any(), any()) } returns "https://test.supabase.co/storage/v1/object/upload/sign/x?token=t"
            coEvery { fileUploadService.fileSize(any(), any()) } returns 1000L
            coEvery { fileUploadService.readHead(any(), any(), any()) } returns mp4Head
            coEvery { fileUploadService.downloadToTempFile(any(), any(), any()) } answers {
                File.createTempFile("test_src", ".tmp").also { downloadedFiles += it }
            }
            coEvery { videoProcessingService.optimizeForStreaming(any<File>()) } returns null
            coEvery { videoProcessingService.extractMetadata(any<File>()) } returns
                VideoProcessingService.VideoMetadata(width = 1080, height = 1920, durationMs = 5000)
            coEvery { videoProcessingService.extractThumbnail(any<File>()) } returns byteArrayOf(4)
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    private fun draftUrl(bucket: String, userId: Int, name: String) =
        StoragePaths.publicUrl(bucket, "users/$userId/$name.jpg")

    private suspend fun createImageDrafts(userId: Int, targetType: MediaDraftTargetType = MediaDraftTargetType.POST, count: Int = 1) =
        mediaDraftService.createImageDrafts(
            userId = userId,
            targetType = targetType,
            files = List(count) { ByteArray(it + 1) },
            contentTypes = List(count) { "image/jpeg" }
        )

    private suspend fun awaitProcessing() = backgroundJob.children.toList().joinAll()

    private suspend fun prepareVideo(userId: Int, targetType: MediaDraftTargetType = MediaDraftTargetType.POST) =
        mediaDraftService.prepareVideoUpload(
            userId = userId,
            targetType = targetType,
            request = VideoUploadPrepareRequest(mimeType = "video/mp4", size = 1000)
        )

    // ===== 이미지 초안 =====

    @Test
    fun `이미지 초안 - 글은 썸네일과 잠금 프리뷰까지 만들고 댓글은 프리뷰가 없다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val postDrafts = createImageDrafts(user.id.value, MediaDraftTargetType.POST, count = 2)
            val commentDraft = createImageDrafts(user.id.value, MediaDraftTargetType.COMMENT).single()

            assertEquals(2, postDrafts.size)
            postDrafts.forEach { draft ->
                assertEquals(MediaType.IMAGE, draft.type)
                assertEquals(100, draft.width)
                assertTrue(draft.url.contains("/posts/users/${user.id.value}/"))
                assertNotNull(draft.thumbnailUrl)
                assertNotNull(draft.previewUrl)
            }
            assertTrue(commentDraft.url.contains("/comments/users/${user.id.value}/"))
            assertNotNull(commentDraft.thumbnailUrl)
            assertNull(commentDraft.previewUrl)

            val rows = query { MediaDraftDao.all().toList() }
            assertEquals(3, rows.size)
            assertTrue(rows.all { it.userId == user.id.value && it.status == MediaDraftStatus.READY && it.expiresAt > nowUtc() })
            assertEquals(listOf(MediaDraftTargetType.POST, MediaDraftTargetType.POST, MediaDraftTargetType.COMMENT), rows.map { it.targetType })
        }
    }

    @Test
    fun `이미지 초안 실패 - 영상 파트, 잘못된 이미지, 개수 초과`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            assertFailsWith<InvalidInputException> {
                mediaDraftService.createImageDrafts(user.id.value, MediaDraftTargetType.POST, listOf(byteArrayOf(1)), listOf("video/mp4"))
            }
            coEvery { imageProcessingService.validateImage(any(), any()) } returns false
            assertFailsWith<ImageValidationFailedException> {
                createImageDrafts(user.id.value)
            }
            coEvery { imageProcessingService.validateImage(any(), any()) } returns true
            assertFailsWith<InvalidInputException> {
                createImageDrafts(user.id.value, count = 11)
            }

            assertEquals(0L, query { MediaDraftDao.all().count() })
            coVerify(exactly = 0) { fileUploadService.uploadDraftMedia(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `이미지 초안 실패 - 도중에 실패하면 이미 올린 파일을 회수하고 행은 남기지 않는다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val firstUrl = draftUrl(StorageConfig.Buckets.POSTS, user.id.value, "draft_1")
            coEvery { fileUploadService.uploadDraftMedia(any(), any(), match { it.size == 2 }, any()) } throws IllegalStateException("업로드 실패")

            assertFailsWith<IllegalStateException> {
                createImageDrafts(user.id.value, count = 2)
            }

            coVerify {
                fileUploadService.deleteByUrls(
                    match { urls ->
                        firstUrl in urls &&
                            draftUrl(StorageConfig.Buckets.POSTS, user.id.value, "thumb") in urls &&
                            draftUrl(StorageConfig.Buckets.POSTS, user.id.value, "preview") in urls
                    }
                )
            }
            assertEquals(0L, query { MediaDraftDao.all().count() })
        }
    }

    @Test
    fun `상품 초안 - 표시 이미지 한 장만 올리고 썸네일·프리뷰는 만들지 않는다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            coEvery { imageProcessingService.generateProductImage(any()) } returns byteArrayOf(7, 7)

            val draft = createImageDrafts(user.id.value, MediaDraftTargetType.PRODUCT).single()

            assertEquals(MediaType.IMAGE, draft.type)
            assertTrue(draft.url.contains("/products/users/${user.id.value}/draft_2"))  // 가공본 크기
            assertNull(draft.thumbnailUrl)
            assertNull(draft.previewUrl)
            coVerify(exactly = 0) { imageProcessingService.generateDisplayImage(any(), any()) }
            coVerify(exactly = 0) { imageProcessingService.generateLockedPreview(any()) }
        }
    }

    @Test
    fun `상품 초안 실패 - 영상 초안은 만들 수 없다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            assertFailsWith<InvalidInputException> {
                prepareVideo(user.id.value, MediaDraftTargetType.PRODUCT)
            }
            assertFailsWith<InvalidInputException> {
                mediaDraftService.createVideoDraft(user.id.value, MediaDraftTargetType.PRODUCT, uploadId = 1)
            }
        }
    }

    // ===== 영상 초안 =====

    @Test
    fun `영상 초안 - 발급은 대상 없이 소유자 폴더로 기록한다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val response = prepareVideo(user.id.value, MediaDraftTargetType.COMMENT)

            val record = query { MediaUploadDao.findById(response.uploadId) }!!
            assertEquals(UploadPurpose.DRAFT_COMMENT_VIDEO, record.purpose)
            assertNull(record.targetId)
            assertEquals(StorageConfig.Buckets.COMMENTS, record.bucket)
            assertTrue(record.path.startsWith("users/${user.id.value}/media_${user.id.value}_"))
            assertTrue(record.path.endsWith(".mp4"))
        }
    }

    @Test
    fun `영상 초안 - 확인 즉시 PROCESSING으로 응답하고 가공이 끝나면 READY로 값이 채워진다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val upload = prepareVideo(user.id.value)

            val draft = mediaDraftService.createVideoDraft(user.id.value, MediaDraftTargetType.POST, upload.uploadId)

            assertEquals(MediaDraftStatus.PROCESSING, draft.status)
            assertEquals(MediaType.VIDEO, draft.type)
            assertNull(draft.width)
            assertTrue(draft.url.contains("/posts/users/${user.id.value}/media_"))
            assertEquals(UploadStatus.CONSUMED, query { MediaUploadDao.findById(upload.uploadId) }!!.status)

            awaitProcessing()

            val ready = mediaDraftService.getDraft(user.id.value, draft.id)
            assertEquals(MediaDraftStatus.READY, ready.status)
            assertEquals(1080, ready.width)
            assertEquals(5000L, ready.durationMs)
            assertNotNull(ready.thumbnailUrl)
            assertNotNull(ready.previewUrl)
            assertEquals(1, downloadedFiles.size)
            assertTrue(downloadedFiles.none { it.exists() }, "가공 뒤 임시파일 삭제")
        }
    }

    @Test
    fun `영상 초안 - 가공에 실패하면 FAILED로 남고 원본은 만료 정리에 맡긴다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val upload = prepareVideo(user.id.value)
            coEvery { videoProcessingService.extractMetadata(any<File>()) } throws IllegalStateException("가공 실패")

            val draft = mediaDraftService.createVideoDraft(user.id.value, MediaDraftTargetType.POST, upload.uploadId)
            awaitProcessing()

            assertEquals(MediaDraftStatus.FAILED, mediaDraftService.getDraft(user.id.value, draft.id).status)
            coVerify(exactly = 0) { fileUploadService.deleteByUrls(any()) }
            assertTrue(downloadedFiles.none { it.exists() })
        }
    }

    @Test
    fun `영상 초안 - 가공 사이 초안이 지워졌으면 파생물을 회수한다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val upload = prepareVideo(user.id.value)
            coEvery { videoProcessingService.extractMetadata(any<File>()) } coAnswers {
                query { MediaDraftDao.all().forEach { it.delete() } }  // 만료 정리가 먼저 지운 상황
                VideoProcessingService.VideoMetadata(width = 1080, height = 1920, durationMs = 5000)
            }

            mediaDraftService.createVideoDraft(user.id.value, MediaDraftTargetType.POST, upload.uploadId)
            awaitProcessing()

            val thumbUrl = draftUrl(StorageConfig.Buckets.POSTS, user.id.value, "thumb")
            val previewUrl = draftUrl(StorageConfig.Buckets.POSTS, user.id.value, "preview")
            coVerify { fileUploadService.deleteByUrls(match { it.toSet() == setOf(thumbUrl, previewUrl) }) }
        }
    }

    @Test
    fun `초안 조회 - 소유자만 볼 수 있다`() {
        runBlocking {
            val (owner, other) = TestFixtures.createTestUsers(count = 2)
            val draft = createImageDrafts(owner.id.value).single()

            assertEquals(draft.id, mediaDraftService.getDraft(owner.id.value, draft.id).id)
            assertFailsWith<NotFoundException> {
                mediaDraftService.getDraft(other.id.value, draft.id)
            }
        }
    }

    @Test
    fun `영상 초안 실패 - 다른 용도로 발급했거나 다른 사람의 업로드`() {
        runBlocking {
            val (owner, other) = TestFixtures.createTestUsers(count = 2)
            val upload = prepareVideo(owner.id.value, MediaDraftTargetType.POST)

            assertFailsWith<NotFoundException> {
                mediaDraftService.createVideoDraft(owner.id.value, MediaDraftTargetType.COMMENT, upload.uploadId)
            }
            assertFailsWith<NotFoundException> {
                mediaDraftService.createVideoDraft(other.id.value, MediaDraftTargetType.POST, upload.uploadId)
            }

            assertEquals(UploadStatus.ISSUED, query { MediaUploadDao.findById(upload.uploadId) }!!.status)
            assertEquals(0L, query { MediaDraftDao.all().count() })
        }
    }

    @Test
    fun `영상 초안 실패 - 사용된 업로드는 초안을 만들지 않는다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val upload = prepareVideo(user.id.value)
            query { mediaUploadService.consumeInTransaction(upload.uploadId) }

            assertFailsWith<ConflictException> {
                mediaDraftService.createVideoDraft(user.id.value, MediaDraftTargetType.POST, upload.uploadId)
            }
            assertEquals(0L, query { MediaDraftDao.all().count() })
        }
    }

    // ===== 게시 회수 =====

    @Test
    fun `게시 회수 - 지정한 순서대로 값을 주고 초안 행을 지운다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val (first, second) = createImageDrafts(user.id.value, count = 2)

            val claimed = query {
                mediaDraftService.claimInTransaction(user.id.value, MediaDraftTargetType.POST, listOf(second.id, first.id))
            }

            assertEquals(listOf(second.url, first.url), claimed.map { it.url })
            assertEquals(second.thumbnailUrl, claimed.first().thumbnailUrl)
            assertEquals(second.previewUrl, claimed.first().previewUrl)
            assertEquals(0L, query { MediaDraftDao.all().count() })

            assertFailsWith<NotFoundException> {
                query { mediaDraftService.claimInTransaction(user.id.value, MediaDraftTargetType.POST, listOf(first.id)) }
            }
        }
    }

    @Test
    fun `게시 회수 실패 - 남의 초안, 다른 용도, 없는 초안, 중복, 개수 초과, 만료, 처리 중`() {
        runBlocking {
            val (owner, other) = TestFixtures.createTestUsers(count = 2)
            val draft = createImageDrafts(owner.id.value).single()

            assertFailsWith<NotFoundException> {
                query { mediaDraftService.claimInTransaction(other.id.value, MediaDraftTargetType.POST, listOf(draft.id)) }
            }
            assertFailsWith<NotFoundException> {
                query { mediaDraftService.claimInTransaction(owner.id.value, MediaDraftTargetType.COMMENT, listOf(draft.id)) }
            }
            assertFailsWith<NotFoundException> {
                query { mediaDraftService.claimInTransaction(owner.id.value, MediaDraftTargetType.POST, listOf(draft.id, draft.id + 1)) }
            }
            assertFailsWith<InvalidInputException> {
                query { mediaDraftService.claimInTransaction(owner.id.value, MediaDraftTargetType.POST, listOf(draft.id, draft.id)) }
            }
            assertFailsWith<InvalidInputException> {
                query { mediaDraftService.claimInTransaction(owner.id.value, MediaDraftTargetType.POST, (1..11).toList()) }
            }

            query { MediaDraftDao.findById(draft.id)!!.expiresAt = pastTime }
            assertFailsWith<InvalidInputException> {
                query { mediaDraftService.claimInTransaction(owner.id.value, MediaDraftTargetType.POST, listOf(draft.id)) }
            }

            query {
                MediaDraftDao.findById(draft.id)!!.apply {
                    expiresAt = nowUtc().let { LocalDateTime(it.year + 1, 1, 1, 0, 0) }
                    status = MediaDraftStatus.PROCESSING
                }
            }
            assertFailsWith<ConflictException> {
                query { mediaDraftService.claimInTransaction(owner.id.value, MediaDraftTargetType.POST, listOf(draft.id)) }
            }

            assertNotNull(query { MediaDraftDao.findById(draft.id) }, "실패한 회수는 초안을 남김")
        }
    }

    // ===== 정리 =====

    @Test
    fun `정리 - 만료 초안은 파일과 행을 지우고 삭제 실패분은 다음 회차에 다시 지운다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val expired = createImageDrafts(user.id.value).single()
            val fresh = createImageDrafts(user.id.value, MediaDraftTargetType.COMMENT).single()
            query { MediaDraftDao.findById(expired.id)!!.expiresAt = pastTime }

            coEvery { fileUploadService.deleteByUrls(any()) } returns false
            assertEquals(0, mediaDraftService.cleanupExpired())
            assertNotNull(query { MediaDraftDao.findById(expired.id) })

            coEvery { fileUploadService.deleteByUrls(any()) } returns true
            assertEquals(1, mediaDraftService.cleanupExpired())

            coVerify {
                fileUploadService.deleteByUrls(
                    match { it.toSet() == setOf(expired.url, expired.thumbnailUrl, expired.previewUrl) }
                )
            }
            assertNull(query { MediaDraftDao.findById(expired.id) })
            assertNotNull(query { MediaDraftDao.findById(fresh.id) })
        }
    }

    @Test
    fun `탈퇴 정리 - 남은 초안 행을 지우고 파일은 삭제 목록에 넘긴다`() {
        runBlocking {
            val (user, other) = TestFixtures.createTestUsers(count = 2)
            val mine = createImageDrafts(user.id.value).single()
            val others = createImageDrafts(other.id.value).single()

            val result = query { TestFixtures.accountCleanupService().cleanupInTransaction(user.id.value) }

            assertTrue(result.fileUrlsToDelete.containsAll(listOfNotNull(mine.url, mine.thumbnailUrl, mine.previewUrl)))
            assertFalse(others.url in result.fileUrlsToDelete)
            assertNull(query { MediaDraftDao.findById(mine.id) })
            assertNotNull(query { MediaDraftDao.findById(others.id) })
        }
    }
}
