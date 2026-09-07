package com.ninezero.core.storage

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.logger
import io.github.jan.supabase.storage.storage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import java.util.*

data class AvatarUploadResult(
    val avatarUrl: String,
    val avatarThumbUrl: String?
)

class FileUploadService {
    private val logger = logger()
    private val storage = StorageConfig.client.storage
    private val imageProcessingService = ImageProcessingService()

    // 외부 이미지 다운로드용
    private val downloadClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
            }
        }
    }

    fun isSupabaseImage(imageUrl: String): Boolean {
        return StorageConfig.Buckets.run {
            imageUrl.contains(AVATARS) ||
                    imageUrl.contains(PRODUCTS) ||
                    imageUrl.contains(POSTS) ||
                    imageUrl.contains(COMMENTS) ||
                    imageUrl.contains(CHAT_IMAGES) ||
                    imageUrl.contains(CHAT_VIDEOS) ||
                    imageUrl.contains(CHAT_FILES) ||
                    imageUrl.contains(REVIEW_IMAGES)
        }
    }

    suspend fun deleteFileIfSupabase(imageUrl: String): Boolean {
        return if (isSupabaseImage(imageUrl)) {
            try {
                val (bucket, path) = extractBucketAndPath(imageUrl)
                    ?: return false

                deleteFile(bucket, path)
            } catch (e: Exception) {
                logger.warn("Supabase 이미지 삭제 실패: {}", e.message)
                false
            }
        } else {
            logger.debug("외부 이미지는 삭제하지 않음: {}", imageUrl)
            true
        }
    }

    fun isSharedSeedFile(url: String): Boolean = url.contains("/0/")  // id=0 폴더는 삭제 대상에서 제외

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
            throw IllegalArgumentException("프로필 이미지는 10MB를 초과할 수 없습니다.")
        }

        val fileName = generateFileName("avatar", userId, extension)
        val path = "users/$userId/$fileName"

        storage.from(StorageConfig.Buckets.AVATARS).upload(path, file)
        val avatarUrl = storage.from(StorageConfig.Buckets.AVATARS).publicUrl(path)

        // 썸네일 실패해도 본 업로드는 성공으로 처리
        val thumbUrl = runCatching {
            val thumbBytes = imageProcessingService.generateSquareThumbnail(file, size = 256)
                ?: return@runCatching null
            val thumbFileName = generateFileName("avatar_thumb", userId, imageProcessingService.imageExtensionForBytes(thumbBytes))
            val thumbPath = "users/$userId/$thumbFileName"
            storage.from(StorageConfig.Buckets.AVATARS).upload(thumbPath, thumbBytes)
            storage.from(StorageConfig.Buckets.AVATARS).publicUrl(thumbPath)
        }.getOrElse {
            logger.warn("아바타 썸네일 생성 실패 (원본 업로드는 성공): {}", it.message)
            null
        }

        return AvatarUploadResult(avatarUrl = avatarUrl, avatarThumbUrl = thumbUrl)
    }

    suspend fun uploadProductImage(
        productId: Int,
        file: ByteArray,
        extension: String
    ): String {
        if (file.size > StorageConfig.FileSizeLimit.PRODUCT) {
            throw IllegalArgumentException("상품 이미지는 10MB를 초과할 수 없습니다.")
        }

        // 1440px WebP 리사이즈
        val processed = imageProcessingService.generateDisplayImage(file, maxDimension = 1440)
            ?: imageProcessingService.convertToWebp(file)
            ?: file
        val fileName = generateFileName("product", productId, imageProcessingService.imageExtensionForBytes(processed))
        val path = "products/$productId/$fileName"

        storage.from(StorageConfig.Buckets.PRODUCTS).upload(path, processed)

        return storage.from(StorageConfig.Buckets.PRODUCTS).publicUrl(path)
    }

    suspend fun uploadProductImages(
        productId: Int,
        files: List<ByteArray>,
        extensions: List<String>
    ): List<String> {
        if (files.size != extensions.size) {
            throw IllegalArgumentException("파일 개수와 확장자 개수가 일치하지 않습니다.")
        }

        return files.mapIndexed { index, file ->
            uploadProductImage(productId, file, extensions[index])
        }
    }

    suspend fun uploadPostMedia(
        postId: Int,
        file: ByteArray,
        extension: String
    ): String {
        if (file.size > StorageConfig.FileSizeLimit.POST) {
            throw IllegalArgumentException("포스트 미디어는 10MB를 초과할 수 없습니다.")
        }

        val fileName = generateFileName("post", postId, extension)
        val path = "posts/$postId/$fileName"

        storage.from(StorageConfig.Buckets.POSTS).upload(path, file)

        return storage.from(StorageConfig.Buckets.POSTS).publicUrl(path)
    }

    suspend fun uploadPostMediaList(
        postId: Int,
        files: List<ByteArray>,
        extensions: List<String>
    ): List<String> {
        if (files.size != extensions.size) {
            throw IllegalArgumentException("파일 개수와 확장자 개수가 일치하지 않습니다.")
        }

        return files.mapIndexed { index, file ->
            uploadPostMedia(postId, file, extensions[index])
        }
    }

    suspend fun uploadPostThumbnail(postId: Int, thumbnail: ByteArray): String {
        val fileName = generateFileName("post_thumb", postId, imageProcessingService.imageExtensionForBytes(thumbnail))
        val path = "posts/$postId/$fileName"
        storage.from(StorageConfig.Buckets.POSTS).upload(path, thumbnail)
        return storage.from(StorageConfig.Buckets.POSTS).publicUrl(path)
    }

    /** 잠금 블러 프리뷰 업로드 */
    suspend fun uploadPostPreview(postId: Int, preview: ByteArray): String {
        val fileName = generateFileName("post_preview", postId, imageProcessingService.imageExtensionForBytes(preview))
        val path = "posts/$postId/$fileName"
        storage.from(StorageConfig.Buckets.POSTS).upload(path, preview)
        return storage.from(StorageConfig.Buckets.POSTS).publicUrl(path)  // 비권한자엔 원본 대신 이 URL만 노출
    }

    suspend fun uploadCommentMedia(
        commentId: Int,
        file: ByteArray,
        extension: String
    ): String {
        if (file.size > StorageConfig.FileSizeLimit.COMMENT) {
            throw IllegalArgumentException("댓글 미디어는 10MB를 초과할 수 없습니다.")
        }

        val fileName = generateFileName("comment", commentId, extension)
        val path = "comments/$commentId/$fileName"

        storage.from(StorageConfig.Buckets.COMMENTS).upload(path, file)

        return storage.from(StorageConfig.Buckets.COMMENTS).publicUrl(path)
    }

    suspend fun uploadCommentMediaList(
        commentId: Int,
        files: List<ByteArray>,
        extensions: List<String>
    ): List<String> {
        if (files.size != extensions.size) {
            throw IllegalArgumentException("파일 개수와 확장자 개수가 일치하지 않습니다.")
        }

        return files.mapIndexed { index, file ->
            uploadCommentMedia(commentId, file, extensions[index])
        }
    }

    suspend fun uploadCommentThumbnail(commentId: Int, thumbnail: ByteArray): String {
        val fileName = generateFileName("comment_thumb", commentId, imageProcessingService.imageExtensionForBytes(thumbnail))
        val path = "comments/$commentId/$fileName"
        storage.from(StorageConfig.Buckets.COMMENTS).upload(path, thumbnail)
        return storage.from(StorageConfig.Buckets.COMMENTS).publicUrl(path)
    }

    suspend fun uploadChatImage(
        roomId: Int,
        senderId: Int,
        file: ByteArray,
        extension: String
    ): Pair<String, String?> {
        if (file.size > StorageConfig.FileSizeLimit.CHAT_IMAGE) {
            throw IllegalArgumentException("채팅 이미지는 10MB를 초과할 수 없습니다.")
        }

        val processed = imageProcessingService.convertToWebp(file) ?: file
        val fileName = generateFileName("chat", senderId, imageProcessingService.imageExtensionForBytes(processed))
        val path = "chats/$roomId/$fileName"

        storage.from(StorageConfig.Buckets.CHAT_IMAGES).upload(path, processed)
        val url = storage.from(StorageConfig.Buckets.CHAT_IMAGES).publicUrl(path)

        // 1080px WebP 리사이즈
        val thumbnailUrl = imageProcessingService.generateDisplayImage(file)?.let { thumbBytes ->
            val thumbName = generateFileName("chat_thumb", senderId, imageProcessingService.imageExtensionForBytes(thumbBytes))
            val thumbPath = "chats/$roomId/$thumbName"
            storage.from(StorageConfig.Buckets.CHAT_IMAGES).upload(thumbPath, thumbBytes)
            storage.from(StorageConfig.Buckets.CHAT_IMAGES).publicUrl(thumbPath)
        }

        return url to thumbnailUrl
    }

    suspend fun uploadChatVideo(
        roomId: Int,
        senderId: Int,
        file: ByteArray,
        extension: String
    ): String {
        if (file.size > StorageConfig.FileSizeLimit.CHAT_VIDEO) {
            throw IllegalArgumentException("채팅 비디오는 50MB를 초과할 수 없습니다.")
        }

        val fileName = generateFileName("chat_video", senderId, extension)
        val path = "chats/$roomId/videos/$fileName"

        storage.from(StorageConfig.Buckets.CHAT_VIDEOS).upload(path, file)

        return storage.from(StorageConfig.Buckets.CHAT_VIDEOS).publicUrl(path)
    }

    suspend fun uploadChatFile(
        roomId: Int,
        senderId: Int,
        file: ByteArray,
        originalFileName: String
    ): String {
        if (file.size > StorageConfig.FileSizeLimit.CHAT_FILE) {
            throw IllegalArgumentException("채팅 파일은 20MB를 초과할 수 없습니다.")
        }

        val uuid = UUID.randomUUID().toString().substring(0, 8)
        val timestamp = System.currentTimeMillis()
        // 원본 파일명의 경로 구분자 제거
        val safeName = originalFileName.replace(Regex("[/\\\\]"), "_")
        val fileName = "chat_file_${senderId}_${timestamp}_${uuid}_$safeName"
        val path = "chats/$roomId/files/$fileName"

        storage.from(StorageConfig.Buckets.CHAT_FILES).upload(path, file)

        return storage.from(StorageConfig.Buckets.CHAT_FILES).publicUrl(path)
    }

    suspend fun uploadReviewImage(
        reviewId: Int,
        file: ByteArray,
        extension: String
    ): String {
        if (file.size > StorageConfig.FileSizeLimit.REVIEW_IMAGE) {
            throw IllegalArgumentException("리뷰 이미지는 10MB를 초과할 수 없습니다.")
        }

        val processed = imageProcessingService.convertToWebp(file) ?: file
        val fileName = generateFileName("review", reviewId, imageProcessingService.imageExtensionForBytes(processed))
        val path = "reviews/$reviewId/$fileName"

        storage.from(StorageConfig.Buckets.REVIEW_IMAGES).upload(path, processed)

        return storage.from(StorageConfig.Buckets.REVIEW_IMAGES).publicUrl(path)
    }

    suspend fun uploadReviewImages(
        reviewId: Int,
        files: List<ByteArray>,
        extensions: List<String>
    ): List<String> {
        if (files.size != extensions.size) {
            throw IllegalArgumentException("파일 개수와 확장자 개수가 일치하지 않습니다.")
        }

        if (files.size > Constants.Commerce.MAX_REVIEW_IMAGES) {
            throw IllegalArgumentException("리뷰 이미지는 최대 ${Constants.Commerce.MAX_REVIEW_IMAGES}개까지 업로드 가능합니다.")
        }

        return files.mapIndexed { index, file ->
            uploadReviewImage(reviewId, file, extensions[index])
        }
    }

    suspend fun deleteFile(bucket: String, path: String): Boolean {
        return try {
            storage.from(bucket).delete(path)
            true
        } catch (e: Exception) {
            logger.warn("파일 삭제 실패: {}/{} - {}", bucket, path, e.message)
            false
        }
    }

    private fun extractPathFromUrl(url: String, bucket: String): String? {
        return try {
            val bucketPrefix = "/storage/v1/object/public/$bucket/"
            val startIndex = url.indexOf(bucketPrefix)
            if (startIndex != -1) {
                url.substring(startIndex + bucketPrefix.length)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractBucketAndPath(url: String): Pair<String, String>? {
        return try {
            StorageConfig.Buckets.ALL.firstNotNullOfOrNull { bucket ->  // 파일명에 다른 버킷명 섞여도 오판 방지
                extractPathFromUrl(url, bucket)?.let { bucket to it }
            }
        } catch (e: Exception) {
            logger.warn("버킷과 경로 추출 실패: {}", e.message)
            null
        }
    }

    private fun generateFileName(prefix: String, id: Int, extension: String): String {
        val uuid = UUID.randomUUID().toString().substring(0, 8)
        val timestamp = System.currentTimeMillis()
        return "${prefix}_${id}_${timestamp}_${uuid}.$extension"
    }
}
