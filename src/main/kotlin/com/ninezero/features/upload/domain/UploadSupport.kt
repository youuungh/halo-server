package com.ninezero.features.upload.domain

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ImageValidationFailedException
import com.ninezero.core.common.exception.InvalidFileException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.VideoProcessingService

/** multipart 파일 파트 검증 */
internal suspend fun validateImageParts(
    imageProcessingService: ImageProcessingService,
    files: List<ByteArray>,
    contentTypes: List<String>,
    maxSize: Long
) {
    if (files.size != contentTypes.size) {
        throw InvalidFileException(Errors.File.FILE_CONTENT_TYPE_COUNT_MISMATCH)
    }
    files.forEachIndexed { index, bytes ->
        if (contentTypes[index].startsWith("video/", ignoreCase = true)) {  // 영상은 직접 업로드로만
            throw InvalidInputException(Errors.File.UPLOAD_ID_REQUIRED)
        }
        if (!imageProcessingService.validateImage(bytes, maxSize)) {
            throw ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
        }
    }
}

/** 직접 업로드 영상 가공 결과 */
internal class UploadedVideo(
    val meta: VideoProcessingService.VideoMetadata,
    val thumbnail: ByteArray?
)

/** 직접 업로드 영상 스트리밍 재정렬과 메타·썸네일 추출 */
internal suspend fun prepareUploadedVideo(
    videoProcessingService: VideoProcessingService,
    fileUploadService: FileUploadService,
    upload: MediaUploadService.VerifiedUpload
): UploadedVideo {
    val source = fileUploadService.downloadToTempFile(upload.bucket, upload.path, upload.size)
    try {
        // 아직 아무도 참조하지 않은 파일이라 덮어써도 옛 캐시가 없음
        videoProcessingService.optimizeForStreaming(source)?.let { fileUploadService.reuploadToUrl(upload.url, it) }
        return UploadedVideo(
            meta = videoProcessingService.extractMetadata(source),
            thumbnail = videoProcessingService.extractThumbnail(source)
        )
    } finally {
        source.delete()  // 재인코딩은 게시 후 저장소에서 다시 받음
    }
}
