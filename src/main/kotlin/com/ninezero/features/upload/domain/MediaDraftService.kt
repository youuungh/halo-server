package com.ninezero.features.upload.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.MediaDraftStatus
import com.ninezero.core.common.config.MediaDraftTargetType
import com.ninezero.core.common.config.MediaType
import com.ninezero.core.common.config.UploadPurpose
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.upload.MediaDraftDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.upload.data.MediaDraftRepository
import com.ninezero.features.upload.fileUrls
import com.ninezero.features.upload.presentation.models.request.VideoUploadPrepareRequest
import com.ninezero.features.upload.presentation.models.response.MediaDraftResponse
import com.ninezero.features.upload.presentation.models.response.UploadPrepareResponse
import com.ninezero.features.upload.toMediaDraftResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class MediaDraftService(
    private val mediaDraftRepository: MediaDraftRepository,
    private val mediaUploadService: MediaUploadService,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val videoProcessingService: VideoProcessingService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()
    private val videoProcessing = Semaphore(VIDEO_PROCESSING_PARALLELISM)

    /** 초안 한 건의 값, 게시 시 미디어 행으로 복사 */
    data class DraftMedia(
        val type: MediaType,
        val url: String,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
        val thumbnailUrl: String?,
        val previewUrl: String?
    ) {
        val fileUrls: List<String> get() = listOfNotNull(url, thumbnailUrl, previewUrl)
        val derivedUrls: List<String> get() = listOfNotNull(thumbnailUrl, previewUrl)
    }

    /** 이미지 파일 파트를 가공해 초안 생성 */
    suspend fun createImageDrafts(
        userId: Int,
        targetType: MediaDraftTargetType,
        files: List<ByteArray>,
        contentTypes: List<String>
    ): List<MediaDraftResponse> {
        if (files.size > targetType.maxCount) {
            throw InvalidInputException(targetType.limitExceededMessage)
        }
        validateImageParts(imageProcessingService, files, contentTypes, targetType.maxSize)

        val prepared = mutableListOf<DraftMedia>()
        try {
            files.forEach { prepared += prepareImage(userId, targetType, it) }
            val expiresAt = draftExpiresAt()
            return query { prepared.map { saveDraft(userId, targetType, it, expiresAt).toMediaDraftResponse() } }
        } catch (e: Exception) {
            fileUploadService.deleteByUrls(prepared.flatMap { it.fileUrls })
            throw e
        }
    }

    /** 초안 영상 서명 업로드 URL 발급 */
    suspend fun prepareVideoUpload(
        userId: Int,
        targetType: MediaDraftTargetType,
        request: VideoUploadPrepareRequest
    ): UploadPrepareResponse {
        return mediaUploadService.prepare(
            userId = userId,
            purpose = targetType.videoPurpose ?: throw InvalidInputException(Errors.File.VIDEO_DRAFT_NOT_ALLOWED),
            targetId = null,
            mimeType = request.mimeType,
            size = request.size
        )
    }

    /** 직접 올린 영상을 PROCESSING 초안으로 받고 가공은 백그라운드 */
    suspend fun createVideoDraft(userId: Int, targetType: MediaDraftTargetType, uploadId: Int): MediaDraftResponse {
        val purpose = targetType.videoPurpose ?: throw InvalidInputException(Errors.File.VIDEO_DRAFT_NOT_ALLOWED)
        val upload = mediaUploadService.verify(uploadId, userId, purpose, targetId = null)
        val expiresAt = draftExpiresAt()
        val draft = query {
            mediaUploadService.consumeInTransaction(upload.uploadId)
            mediaDraftRepository.createDraft(
                userId = userId,
                targetType = targetType,
                type = MediaType.VIDEO,
                url = upload.url,
                width = null,
                height = null,
                durationMs = null,
                thumbnailUrl = null,
                previewUrl = null,
                status = MediaDraftStatus.PROCESSING,
                expiresAt = expiresAt
            )
        }
        launchVideoProcessing(draft.id.value, userId, targetType, upload)
        return draft.toMediaDraftResponse()
    }

    /** 소유자의 초안 조회, 영상 가공 상태 확인용 */
    suspend fun getDraft(userId: Int, draftId: Int): MediaDraftResponse {
        val draft = query { mediaDraftRepository.findDraftById(draftId) }
            ?.takeIf { it.userId == userId }
            ?: throw NotFoundException(Errors.File.MEDIA_DRAFT_NOT_FOUND)
        return draft.toMediaDraftResponse()
    }

    /** 게시 트랜잭션 안에서 초안을 거두고 값만 반환 */
    suspend fun claimInTransaction(userId: Int, targetType: MediaDraftTargetType, draftIds: List<Int>): List<DraftMedia> {
        if (draftIds.size != draftIds.toSet().size) {
            throw InvalidInputException(Errors.File.DUPLICATE_MEDIA_DRAFT)
        }
        if (draftIds.size > targetType.maxCount) {
            throw InvalidInputException(targetType.limitExceededMessage)
        }

        val drafts = mediaDraftRepository.findDraftsByIdsForUpdate(draftIds).associateBy { it.id.value }
        val ordered = draftIds.map { draftId ->
            drafts[draftId]?.takeIf { it.userId == userId && it.targetType == targetType }
                ?: throw NotFoundException(Errors.File.MEDIA_DRAFT_NOT_FOUND)
        }
        if (ordered.any { it.status != MediaDraftStatus.READY }) {
            throw ConflictException(Errors.File.MEDIA_DRAFT_NOT_READY)
        }
        val now = nowUtc()
        if (ordered.any { it.expiresAt < now }) {
            throw InvalidInputException(Errors.File.MEDIA_DRAFT_EXPIRED)
        }

        val media = ordered.map { it.toDraftMedia() }
        mediaDraftRepository.deleteDrafts(draftIds)
        return media
    }

    /** 만료된 초안의 파일과 행 삭제 */
    suspend fun cleanupExpired(): Int {
        val cutoff = (Clock.System.now() - CLEANUP_GRACE).toLocalDateTime(TimeZone.UTC)  // 게시 도중인 초안의 파일을 지우지 않는 유예
        var cleaned = 0

        while (true) {
            val expired = query { mediaDraftRepository.findExpiredDrafts(cutoff, CLEANUP_BATCH) }
            if (expired.isEmpty()) break

            // 파일 삭제 실패분은 다음 회차에 재시도
            if (!fileUploadService.deleteByUrls(expired.flatMap { it.fileUrls })) break
            query { mediaDraftRepository.deleteDrafts(expired.map { it.id.value }) }
            cleaned += expired.size

            if (expired.size < CLEANUP_BATCH) break
        }
        return cleaned
    }

    // 공통 보조
    private suspend fun prepareImage(userId: Int, targetType: MediaDraftTargetType, bytes: ByteArray): DraftMedia {
        if (targetType == MediaDraftTargetType.PRODUCT) return prepareProductImage(userId, bytes)

        // 원본 업로드 뒤 실패 방지로 파생물 먼저 생성
        val dimensions = imageProcessingService.extractImageDimensions(bytes)
        val display = imageProcessingService.generateDisplayImage(bytes)
        val preview = if (targetType.hasLockedPreview) imageProcessingService.generateLockedPreview(bytes) else null
        val url = fileUploadService.uploadDraftMedia(targetType.bucket, userId, bytes, imageProcessingService.imageExtensionForBytes(bytes))
        return DraftMedia(
            type = MediaType.IMAGE,
            url = url,
            width = dimensions?.first,
            height = dimensions?.second,
            durationMs = null,
            thumbnailUrl = display?.let { runCatching { fileUploadService.uploadDraftThumbnail(targetType.bucket, userId, it) }.getOrNull() },
            previewUrl = preview?.let { runCatching { fileUploadService.uploadDraftPreview(targetType.bucket, userId, it) }.getOrNull() }
        )
    }

    /** 상품 이미지는 표시용 한 장뿐, 썸네일·프리뷰 없음 */
    private suspend fun prepareProductImage(userId: Int, bytes: ByteArray): DraftMedia {
        val dimensions = imageProcessingService.extractImageDimensions(bytes)
        val processed = imageProcessingService.generateProductImage(bytes)
        val url = fileUploadService.uploadDraftMedia(
            MediaDraftTargetType.PRODUCT.bucket, userId, processed, imageProcessingService.imageExtensionForBytes(processed)
        )
        return DraftMedia(
            type = MediaType.IMAGE,
            url = url,
            width = dimensions?.first,
            height = dimensions?.second,
            durationMs = null,
            thumbnailUrl = null,
            previewUrl = null
        )
    }

    private suspend fun prepareVideo(userId: Int, targetType: MediaDraftTargetType, upload: MediaUploadService.VerifiedUpload): DraftMedia {
        val video = prepareUploadedVideo(videoProcessingService, fileUploadService, upload)
        val preview = if (targetType.hasLockedPreview) video.thumbnail?.let { imageProcessingService.generateLockedPreview(it) } else null
        return DraftMedia(
            type = MediaType.VIDEO,
            url = upload.url,
            width = video.meta.width,
            height = video.meta.height,
            durationMs = video.meta.durationMs,
            thumbnailUrl = video.thumbnail?.let { runCatching { fileUploadService.uploadDraftThumbnail(targetType.bucket, userId, it) }.getOrNull() },
            previewUrl = preview?.let { runCatching { fileUploadService.uploadDraftPreview(targetType.bucket, userId, it) }.getOrNull() }
        )
    }

    /** 가공을 마치면 READY, 실패하면 FAILED */
    private fun launchVideoProcessing(draftId: Int, userId: Int, targetType: MediaDraftTargetType, upload: MediaUploadService.VerifiedUpload) {
        coroutineScope.launch {
            val media = try {
                videoProcessing.withPermit { prepareVideo(userId, targetType, upload) }
            } catch (e: Exception) {
                logger.warn("영상 초안 가공 실패 draftId={}: {}", draftId, e.message)
                query { mediaDraftRepository.markFailed(draftId) }  // 원본은 만료 정리가 삭제
                return@launch
            }
            val updated = query {
                mediaDraftRepository.markReady(draftId, media.width, media.height, media.durationMs, media.thumbnailUrl, media.previewUrl)
            }
            if (!updated) fileUploadService.deleteByUrls(media.derivedUrls)  // 가공 사이 만료 정리로 지워진 초안
        }
    }

    private suspend fun saveDraft(userId: Int, targetType: MediaDraftTargetType, media: DraftMedia, expiresAt: LocalDateTime): MediaDraftDao =
        mediaDraftRepository.createDraft(
            userId = userId,
            targetType = targetType,
            type = media.type,
            url = media.url,
            width = media.width,
            height = media.height,
            durationMs = media.durationMs,
            thumbnailUrl = media.thumbnailUrl,
            previewUrl = media.previewUrl,
            status = MediaDraftStatus.READY,
            expiresAt = expiresAt
        )

    private fun MediaDraftDao.toDraftMedia() = DraftMedia(
        type = type,
        url = url,
        width = width,
        height = height,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        previewUrl = previewUrl
    )

    private fun draftExpiresAt(): LocalDateTime = (Clock.System.now() + DRAFT_TTL).toLocalDateTime(TimeZone.UTC)

    private companion object {
        val DRAFT_TTL = 2.hours
        val CLEANUP_GRACE = 1.hours
        const val CLEANUP_BATCH = 500
        const val VIDEO_PROCESSING_PARALLELISM = 2  // ffmpeg 디스패처와 같은 수
    }
}

private val MediaDraftTargetType.bucket: String
    get() = when (this) {
        MediaDraftTargetType.POST -> StorageConfig.Buckets.POSTS
        MediaDraftTargetType.COMMENT -> StorageConfig.Buckets.COMMENTS
        MediaDraftTargetType.PRODUCT -> StorageConfig.Buckets.PRODUCTS
    }

private val MediaDraftTargetType.maxSize: Long
    get() = when (this) {
        MediaDraftTargetType.POST -> StorageConfig.FileSizeLimit.POST
        MediaDraftTargetType.COMMENT -> StorageConfig.FileSizeLimit.COMMENT
        MediaDraftTargetType.PRODUCT -> StorageConfig.FileSizeLimit.PRODUCT
    }

/** 한 번에 만들거나 거둘 수 있는 초안 수 */
private val MediaDraftTargetType.maxCount: Int
    get() = when (this) {
        MediaDraftTargetType.POST, MediaDraftTargetType.COMMENT -> Constants.Social.MAX_MEDIA_ATTACHMENTS_PER_POST
        MediaDraftTargetType.PRODUCT -> Constants.Commerce.MAX_PRODUCT_DETAIL_CONTENT_IMAGES
    }

private val MediaDraftTargetType.limitExceededMessage: String
    get() = when (this) {
        MediaDraftTargetType.POST, MediaDraftTargetType.COMMENT -> Errors.Social.Media.ATTACHMENT_LIMIT_EXCEEDED
        MediaDraftTargetType.PRODUCT -> Errors.Commerce.Product.PRODUCT_DETAIL_CONTENT_IMAGES_COUNT_EXCEEDED
    }

/** 영상을 받는 업로드 용도, 상품은 없음 */
private val MediaDraftTargetType.videoPurpose: UploadPurpose?
    get() = when (this) {
        MediaDraftTargetType.POST -> UploadPurpose.DRAFT_POST_VIDEO
        MediaDraftTargetType.COMMENT -> UploadPurpose.DRAFT_COMMENT_VIDEO
        MediaDraftTargetType.PRODUCT -> null
    }

/** 잠금 블러 프리뷰는 글에만 */
private val MediaDraftTargetType.hasLockedPreview: Boolean
    get() = this == MediaDraftTargetType.POST
