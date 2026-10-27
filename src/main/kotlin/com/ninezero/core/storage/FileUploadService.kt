package com.ninezero.core.storage

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.logger
import io.github.jan.supabase.storage.storage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.minutes

// 직접 업로드 원본 내려받기
private val SIGNED_DOWNLOAD_TTL = 10.minutes
private val STORAGE_DOWNLOAD_TIMEOUT = 5.minutes

data class AvatarUploadResult(
    val avatarUrl: String,
    val avatarThumbUrl: String?
)

class FileUploadService(
    private val imageProcessingService: ImageProcessingService
) {
    private val logger = logger()
    private val storage = StorageConfig.client.storage

    // 외부 이미지 다운로드용
    private val downloadClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
            }
        }
    }

    // 삭제

    /** 외부 URL·시드 공용 파일을 건너뛰는 삭제 */
    suspend fun deleteByUrl(imageUrl: String): Boolean {
        if (isSharedSeedFile(imageUrl)) return true

        val (bucket, path) = extractBucketAndPath(imageUrl) ?: run {
            logger.debug("내 스토리지 파일이 아니라 삭제하지 않음: {}", imageUrl)
            return true
        }
        return deleteFile(bucket, path)
    }

    /** URL 목록을 bucket별로 묶어 삭제 */
    suspend fun deleteByUrls(imageUrls: List<String>): Boolean {
        return imageUrls.asSequence()
            .filterNot { isSharedSeedFile(it) }
            .mapNotNull { extractBucketAndPath(it) }
            .groupBy({ it.first }, { it.second })
            .map { (bucket, paths) -> deleteFiles(bucket, paths) }
            .all { it }
    }

    private fun isSharedSeedFile(url: String): Boolean = url.contains("/0/")  // id=0 폴더는 삭제 대상에서 제외

    private suspend fun deleteFile(bucket: String, path: String): Boolean {
        return try {
            storage.from(bucket).delete(path)
            true
        } catch (e: Exception) {
            logger.warn("파일 삭제 실패: {}/{} - {}", bucket, path, e.message)
            false
        }
    }

    suspend fun deleteFiles(bucket: String, paths: List<String>): Boolean {
        return paths.isEmpty() || try {
            storage.from(bucket).delete(paths)
            true
        } catch (e: Exception) {
            logger.warn("파일 일괄 삭제 실패: {} {}건 - {}", bucket, paths.size, e.message)
            false
        }
    }

    // 아바타

    suspend fun uploadAvatarFromUrl(userId: Int, imageUrl: String): AvatarUploadResult? {
        return try {
            val response = downloadClient.get(imageUrl)
            if (!response.status.isSuccess()) {
                logger.warn("소셜 아바타 다운로드 실패: {} ({})", response.status, imageUrl)
                return null
            }

            val bytes = response.body<ByteArray>()
            if (bytes.isEmpty() || bytes.size > StorageConfig.FileSizeLimit.AVATAR) {
                logger.warn("소셜 아바타 크기 부적합: {} bytes", bytes.size)
                return null
            }

            uploadAvatar(userId, bytes, imageProcessingService.imageExtensionForBytes(bytes))
        } catch (e: Exception) {
            logger.warn("소셜 아바타 가져오기 실패: {}", e.message)
            null
        }
    }

    suspend fun uploadAvatar(
        userId: Int,
        file: ByteArray,
        extension: String
    ): AvatarUploadResult {
        if (file.size > StorageConfig.FileSizeLimit.AVATAR) {
            throw IllegalArgumentException("프로필 이미지는 ${StorageConfig.FileSizeLimit.AVATAR / 1024 / 1024}MB를 초과할 수 없습니다.")
        }

        val fileName = StoragePaths.fileName("avatar", userId, extension)
        val path = "users/$userId/$fileName"

        storage.from(StorageConfig.Buckets.AVATARS).upload(path, file)
        val avatarUrl = StoragePaths.publicUrl(StorageConfig.Buckets.AVATARS, path)

        // 썸네일 실패해도 본 업로드는 성공으로 처리
        val thumbUrl = runCatching {
            val thumbBytes = imageProcessingService.generateSquareThumbnail(file, size = 256)
                ?: return@runCatching null
            val thumbFileName = StoragePaths.fileName("avatar_thumb", userId, imageProcessingService.imageExtensionForBytes(thumbBytes))
            val thumbPath = "users/$userId/$thumbFileName"
            storage.from(StorageConfig.Buckets.AVATARS).upload(thumbPath, thumbBytes)
            StoragePaths.publicUrl(StorageConfig.Buckets.AVATARS, thumbPath)
        }.getOrElse {
            logger.warn("아바타 썸네일 생성 실패 (원본 업로드는 성공): {}", it.message)
            null
        }

        return AvatarUploadResult(avatarUrl = avatarUrl, avatarThumbUrl = thumbUrl)
    }

    // 상품·리뷰

    suspend fun uploadProductImage(
        userId: Int,
        file: ByteArray
    ): String {
        if (file.size > StorageConfig.FileSizeLimit.PRODUCT) {
            throw IllegalArgumentException("상품 이미지는 ${StorageConfig.FileSizeLimit.PRODUCT / 1024 / 1024}MB를 초과할 수 없습니다.")
        }

        val processed = imageProcessingService.generateProductImage(file)
        val path = StoragePaths.mediaPath(userId, imageProcessingService.imageExtensionForBytes(processed))

        storage.from(StorageConfig.Buckets.PRODUCTS).upload(path, processed)

        return StoragePaths.publicUrl(StorageConfig.Buckets.PRODUCTS, path)
    }

    private suspend fun uploadReviewImage(
        reviewId: Int,
        file: ByteArray
    ): String {
        if (file.size > StorageConfig.FileSizeLimit.REVIEW_IMAGE) {
            throw IllegalArgumentException("리뷰 이미지는 ${StorageConfig.FileSizeLimit.REVIEW_IMAGE / 1024 / 1024}MB를 초과할 수 없습니다.")
        }

        val processed = imageProcessingService.convertToWebp(file) ?: file
        val fileName = StoragePaths.fileName("review", reviewId, imageProcessingService.imageExtensionForBytes(processed))
        val path = "reviews/$reviewId/$fileName"

        storage.from(StorageConfig.Buckets.REVIEW_IMAGES).upload(path, processed)

        return StoragePaths.publicUrl(StorageConfig.Buckets.REVIEW_IMAGES, path)
    }

    suspend fun uploadReviewImages(
        reviewId: Int,
        files: List<ByteArray>
    ): List<String> {
        if (files.size > Constants.Commerce.MAX_REVIEW_IMAGES) {
            throw IllegalArgumentException("리뷰 이미지는 최대 ${Constants.Commerce.MAX_REVIEW_IMAGES}개까지 업로드 가능합니다.")
        }

        return files.map { file ->
            uploadReviewImage(reviewId, file)
        }
    }

    // 포스트·댓글

    suspend fun uploadPostMedia(
        userId: Int,
        file: ByteArray,
        extension: String
    ): String {
        if (file.size > StorageConfig.FileSizeLimit.POST) {
            throw IllegalArgumentException("포스트 미디어는 ${StorageConfig.FileSizeLimit.POST / 1024 / 1024}MB를 초과할 수 없습니다.")
        }

        val path = StoragePaths.mediaPath(userId, extension)

        storage.from(StorageConfig.Buckets.POSTS).upload(path, file)

        return StoragePaths.publicUrl(StorageConfig.Buckets.POSTS, path)
    }

    suspend fun uploadPostThumbnail(userId: Int, thumbnail: ByteArray): String {
        val path = StoragePaths.mediaPath(userId, imageProcessingService.imageExtensionForBytes(thumbnail))
        storage.from(StorageConfig.Buckets.POSTS).upload(path, thumbnail)
        return StoragePaths.publicUrl(StorageConfig.Buckets.POSTS, path)
    }

    suspend fun uploadPostPreview(userId: Int, preview: ByteArray): String {
        val path = StoragePaths.mediaPath(userId, imageProcessingService.imageExtensionForBytes(preview))
        storage.from(StorageConfig.Buckets.POSTS).upload(path, preview)
        return StoragePaths.publicUrl(StorageConfig.Buckets.POSTS, path)  // 비권한자엔 원본 대신 이 URL만 노출
    }

    suspend fun uploadCommentMedia(
        userId: Int,
        file: ByteArray,
        extension: String
    ): String {
        if (file.size > StorageConfig.FileSizeLimit.COMMENT) {
            throw IllegalArgumentException("댓글 미디어는 ${StorageConfig.FileSizeLimit.COMMENT / 1024 / 1024}MB를 초과할 수 없습니다.")
        }

        val path = StoragePaths.mediaPath(userId, extension)

        storage.from(StorageConfig.Buckets.COMMENTS).upload(path, file)

        return StoragePaths.publicUrl(StorageConfig.Buckets.COMMENTS, path)
    }

    suspend fun uploadCommentThumbnail(userId: Int, thumbnail: ByteArray): String {
        val path = StoragePaths.mediaPath(userId, imageProcessingService.imageExtensionForBytes(thumbnail))
        storage.from(StorageConfig.Buckets.COMMENTS).upload(path, thumbnail)
        return StoragePaths.publicUrl(StorageConfig.Buckets.COMMENTS, path)
    }

    // 초안

    /** 글·댓글에 붙기 전 초안 미디어 업로드 */
    suspend fun uploadDraftMedia(bucket: String, userId: Int, file: ByteArray, extension: String): String {
        val path = StoragePaths.mediaPath(userId, extension)
        storage.from(bucket).upload(path, file)
        return StoragePaths.publicUrl(bucket, path)
    }

    suspend fun uploadDraftThumbnail(bucket: String, userId: Int, thumbnail: ByteArray): String {
        val path = StoragePaths.mediaPath(userId, imageProcessingService.imageExtensionForBytes(thumbnail))
        storage.from(bucket).upload(path, thumbnail)
        return StoragePaths.publicUrl(bucket, path)
    }

    /** 잠금 블러 프리뷰 업로드 */
    suspend fun uploadDraftPreview(bucket: String, userId: Int, preview: ByteArray): String {
        val path = StoragePaths.mediaPath(userId, imageProcessingService.imageExtensionForBytes(preview))
        storage.from(bucket).upload(path, preview)
        return StoragePaths.publicUrl(bucket, path)
    }

    // 채팅

    suspend fun uploadChatImage(
        roomId: Int,
        senderId: Int,
        file: ByteArray
    ): Pair<String, String?> {
        if (file.size > StorageConfig.FileSizeLimit.CHAT_IMAGE) {
            throw IllegalArgumentException("채팅 이미지는 ${StorageConfig.FileSizeLimit.CHAT_IMAGE / 1024 / 1024}MB를 초과할 수 없습니다.")
        }

        val processed = imageProcessingService.convertToWebp(file) ?: file
        val fileName = StoragePaths.fileName("chat", senderId, imageProcessingService.imageExtensionForBytes(processed))
        val path = "chats/$roomId/$fileName"

        storage.from(StorageConfig.Buckets.CHAT_IMAGES).upload(path, processed)
        val url = StoragePaths.publicUrl(StorageConfig.Buckets.CHAT_IMAGES, path)

        // 1080px WebP 리사이즈
        val thumbnailUrl = imageProcessingService.generateDisplayImage(file)?.let { thumbBytes ->
            val thumbName = StoragePaths.fileName("chat_thumb", senderId, imageProcessingService.imageExtensionForBytes(thumbBytes))
            val thumbPath = "chats/$roomId/$thumbName"
            storage.from(StorageConfig.Buckets.CHAT_IMAGES).upload(thumbPath, thumbBytes)
            StoragePaths.publicUrl(StorageConfig.Buckets.CHAT_IMAGES, thumbPath)
        }

        return url to thumbnailUrl
    }

    // 트랜스코딩 교체

    suspend fun reuploadToUrl(url: String, bytes: ByteArray): Boolean {
        val (bucket, path) = extractBucketAndPath(url) ?: run {
            logger.warn("재업로드 경로 추출 실패: {}", url)
            return false
        }
        return try {
            storage.from(bucket).upload(path, bytes) { upsert = true }  // 트랜스코딩 결과 덮어써도 URL 유지
            true
        } catch (e: Exception) {
            logger.warn("재업로드(트랜스코딩 교체) 실패 path={}: {}", path, e.message)
            false
        }
    }

    // 직접 업로드

    /** 덮어쓰기 금지 서명 업로드 URL */
    suspend fun createSignedUploadUrl(bucket: String, path: String): String =
        storage.from(bucket).createSignedUploadUrl(path, upsert = false).url

    suspend fun fileSize(bucket: String, path: String): Long? {
        return try {
            storage.from(bucket).info(path).size
        } catch (e: Exception) {
            logger.warn("파일 정보 조회 실패: {}/{} - {}", bucket, path, e.message)
            null
        }
    }

    suspend fun readHead(bucket: String, path: String, length: Int): ByteArray =
        storage.from(bucket).downloadAuthenticated(path) {
            httpOverride { header(HttpHeaders.Range, "bytes=0-${length - 1}") }
        }

    /** 우리 공개 URL 파일의 임시파일 다운로드 */
    suspend fun downloadToTempFile(url: String): File {
        val (bucket, path) = extractBucketAndPath(url)
            ?: throw IllegalArgumentException("우리 스토리지 파일이 아닙니다: $url")
        val size = fileSize(bucket, path)
            ?: throw IllegalStateException("파일 정보를 찾을 수 없습니다: $url")
        return downloadToTempFile(bucket, path, size)
    }

    /** 임시파일 스트리밍 다운로드 */
    suspend fun downloadToTempFile(bucket: String, path: String, expectedSize: Long): File {
        // 쓰기 완료·응답 상태를 안 보는 SDK 대신 직접 받기
        // CDN의 옛 파일을 피해 서명 URL
        val signedUrl = storage.from(bucket).createSignedUrl(path, SIGNED_DOWNLOAD_TTL)
        val file = File.createTempFile("upload_src", ".tmp")
        try {
            downloadClient.prepareGet(signedUrl) {
                timeout { requestTimeoutMillis = STORAGE_DOWNLOAD_TIMEOUT.inWholeMilliseconds }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("파일 다운로드 실패: ${response.status}")
                }
                val copied = withContext(Dispatchers.IO) {
                    file.outputStream().use { response.bodyAsChannel().copyTo(it) }
                }
                // 끊겨도 예외 없이 끝날 수 있어 크기 확인
                if (copied != expectedSize) {
                    throw IllegalStateException("파일 다운로드가 끊겼습니다: $copied/$expectedSize")
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    private fun extractBucketAndPath(url: String): Pair<String, String>? =
        StorageConfig.Buckets.ALL.firstNotNullOfOrNull { bucket ->  // 내 공개 주소로 시작하는 URL만 인정
            StoragePaths.pathOf(url, bucket)?.let { bucket to it }
        }
}
