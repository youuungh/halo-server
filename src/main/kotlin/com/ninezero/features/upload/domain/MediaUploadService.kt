package com.ninezero.features.upload.domain

import com.ninezero.core.common.config.UploadPurpose
import com.ninezero.core.common.config.UploadStatus
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.core.storage.StoragePaths
import com.ninezero.features.upload.data.MediaUploadRepository
import com.ninezero.features.upload.presentation.models.response.UploadPrepareResponse
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** 앱이 스토리지에 직접 올리는 파일의 발급·확인·정리 */
class MediaUploadService(
    private val mediaUploadRepository: MediaUploadRepository,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService
) {

    /** 확인을 마친 업로드 */
    data class VerifiedUpload(
        val uploadId: Int,
        val bucket: String,
        val path: String,
        val size: Long
    ) {
        val url: String get() = StoragePaths.publicUrl(bucket, path)
    }

    /** 서명 업로드 URL 발급 */
    suspend fun prepare(
        userId: Int,
        purpose: UploadPurpose,
        targetId: Int?,
        mimeType: String,
        size: Long,
        originalFileName: String? = null
    ): UploadPrepareResponse {
        if (mimeType.isBlank() || mimeType.length > MIME_TYPE_MAX_LENGTH) {
            throw InvalidInputException(Errors.File.INVALID_MEDIA_FILE)
        }
        if (size <= 0 || size > purpose.maxSize) {
            throw InvalidInputException(purpose.sizeExceededMessage)
        }
        if (purpose.isVideo && mimeType !in StorageConfig.AllowedMimeTypes.VIDEOS) {
            throw InvalidInputException(Errors.File.VIDEO_UNSUPPORTED_FORMAT)
        }

        val extension = if (purpose.isVideo) videoExtension(mimeType) else fileExtension(originalFileName)
        val path = StoragePaths.uploadPath(purpose, targetId, userId, extension)  // 경로·파일명은 서버가 정함
        val uploadUrl = fileUploadService.createSignedUploadUrl(purpose.bucket, path)
        val expiresAt = (Clock.System.now() + ISSUE_TTL).toLocalDateTime(TimeZone.UTC)

        val upload = query {
            mediaUploadRepository.createUpload(userId, purpose, targetId, purpose.bucket, path, mimeType, size, expiresAt)
        }

        return UploadPrepareResponse(
            uploadId = upload.id.value,
            uploadUrl = uploadUrl,
            contentType = mimeType,
            expiresAt = expiresAt
        )
    }

    /** 트랜잭션 밖에서 부르는 직접 업로드 확인 */
    suspend fun verify(uploadId: Int, userId: Int, purpose: UploadPurpose, targetId: Int?): VerifiedUpload {
        val upload = query { mediaUploadRepository.findUploadById(uploadId) }
            ?.takeIf { it.userId == userId && it.purpose == purpose && it.targetId == targetId }
            ?: throw NotFoundException(Errors.File.UPLOAD_NOT_FOUND)

        if (upload.status == UploadStatus.CONSUMED) {
            throw ConflictException(Errors.File.UPLOAD_ALREADY_USED)
        }
        if (upload.expiresAt < nowUtc()) {
            throw InvalidInputException(Errors.File.UPLOAD_EXPIRED)
        }

        val size = fileUploadService.fileSize(upload.bucket, upload.path)
            ?: throw InvalidInputException(Errors.File.UPLOAD_NOT_COMPLETED)
        if (size <= 0 || size > purpose.maxSize) {
            throw InvalidInputException(purpose.sizeExceededMessage)
        }

        // 형식은 앱이 적어 보낸 값이라 앞부분 바이트로 확인
        if (purpose.isVideo &&
            !imageProcessingService.isValidVideoFormat(fileUploadService.readHead(upload.bucket, upload.path, VIDEO_HEAD_BYTES))
        ) {
            throw InvalidInputException(Errors.File.VIDEO_UNSUPPORTED_FORMAT)
        }

        return VerifiedUpload(upload.id.value, upload.bucket, upload.path, size)
    }

    /** 업로드 사용 처리 */
    suspend fun consumeInTransaction(uploadId: Int) {
        val upload = mediaUploadRepository.findUploadByIdForUpdate(uploadId)
            ?: throw NotFoundException(Errors.File.UPLOAD_NOT_FOUND)
        if (upload.status == UploadStatus.CONSUMED) {
            throw ConflictException(Errors.File.UPLOAD_ALREADY_USED)
        }
        mediaUploadRepository.markConsumed(uploadId, nowUtc())
    }

    /** 만료된 미사용 업로드의 파일·기록과 오래된 사용 기록 삭제 */
    suspend fun cleanupExpired(): Int {
        val now = Clock.System.now()
        val cutoff = (now - CLEANUP_GRACE).toLocalDateTime(TimeZone.UTC)  // 늦게 올라온 파일도 남기지 않는 유예
        var cleaned = 0

        while (true) {
            val expired = query { mediaUploadRepository.findExpiredIssuedUploads(cutoff, CLEANUP_BATCH) }
            if (expired.isEmpty()) break

            // 파일 삭제 실패분은 다음 회차에 재시도
            val deletedIds = expired.groupBy { it.bucket }.flatMap { (bucket, uploads) ->
                if (fileUploadService.deleteFiles(bucket, uploads.map { it.path })) uploads.map { it.id.value } else emptyList()
            }
            query { mediaUploadRepository.deleteUploads(deletedIds) }
            cleaned += deletedIds.size

            if (deletedIds.size < expired.size || expired.size < CLEANUP_BATCH) break
        }

        query {
            mediaUploadRepository.deleteConsumedUploadsBefore((now - CONSUMED_RETENTION).toLocalDateTime(TimeZone.UTC))
        }
        return cleaned
    }

    private fun videoExtension(mimeType: String): String = when (mimeType) {
        "video/quicktime" -> "mov"
        "video/x-msvideo" -> "avi"
        else -> "mp4"
    }

    /** 원래 파일명 확장자 */
    private fun fileExtension(fileName: String?): String =
        fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { EXTENSION_PATTERN.matches(it) } ?: "bin"

    private companion object {
        val ISSUE_TTL = 2.hours
        val CLEANUP_GRACE = 1.hours
        val CONSUMED_RETENTION = 1.days
        const val CLEANUP_BATCH = 500
        const val VIDEO_HEAD_BYTES = 16
        const val MIME_TYPE_MAX_LENGTH = 100  // media_uploads.mime_type 길이
        val EXTENSION_PATTERN = Regex("[a-z0-9]{1,10}")
    }
}

private val UploadPurpose.bucket: String
    get() = when (this) {
        UploadPurpose.CHAT_VIDEO -> StorageConfig.Buckets.CHAT_VIDEOS
        UploadPurpose.CHAT_FILE -> StorageConfig.Buckets.CHAT_FILES
        UploadPurpose.POST_VIDEO, UploadPurpose.DRAFT_POST_VIDEO -> StorageConfig.Buckets.POSTS
        UploadPurpose.DRAFT_COMMENT_VIDEO -> StorageConfig.Buckets.COMMENTS
    }

private val UploadPurpose.maxSize: Long
    get() = when (this) {
        UploadPurpose.CHAT_VIDEO -> StorageConfig.FileSizeLimit.CHAT_VIDEO
        UploadPurpose.CHAT_FILE -> StorageConfig.FileSizeLimit.CHAT_FILE
        UploadPurpose.POST_VIDEO, UploadPurpose.DRAFT_POST_VIDEO -> StorageConfig.FileSizeLimit.POST
        UploadPurpose.DRAFT_COMMENT_VIDEO -> StorageConfig.FileSizeLimit.COMMENT
    }

private val UploadPurpose.isVideo: Boolean
    get() = this != UploadPurpose.CHAT_FILE

private val UploadPurpose.sizeExceededMessage: String
    get() = when (this) {
        UploadPurpose.CHAT_VIDEO -> Errors.File.VIDEO_SIZE_EXCEEDED
        UploadPurpose.CHAT_FILE -> Errors.File.FILE_SIZE_EXCEEDED
        UploadPurpose.POST_VIDEO, UploadPurpose.DRAFT_POST_VIDEO, UploadPurpose.DRAFT_COMMENT_VIDEO -> Errors.File.MEDIA_VALIDATION_FAILED
    }
