package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.social.*
import com.ninezero.features.social.communityCreatorId
import com.ninezero.features.social.data.BookmarkRepository
import com.ninezero.features.social.data.LikeRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.invalidatePostCaches
import com.ninezero.features.social.presentation.models.request.MediaItemRef
import com.ninezero.features.social.presentation.models.response.*
import com.ninezero.features.upload.domain.MediaUploadService
import com.ninezero.features.upload.domain.prepareUploadedVideo
import com.ninezero.features.upload.domain.validateImageParts
import com.ninezero.features.upload.presentation.models.request.VideoUploadPrepareRequest
import com.ninezero.features.upload.presentation.models.response.UploadPrepareResponse
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toUserSummaryResponse
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.sql.SortOrder

/** 포스트 수정 시 미디어 교체와 영상 직접 업로드 준비 */
class PostMediaService(
    private val postRepository: PostRepository,
    private val userRepository: UserRepository,
    private val likeRepository: LikeRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val mediaUploadService: MediaUploadService,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val videoProcessingService: VideoProcessingService,
    private val cacheService: CacheService,
    private val coroutineScope: CoroutineScope
) {

    /** 포스트 영상 직접 업로드 준비 */
    suspend fun preparePostVideoUpload(
        postId: Int,
        userId: Int,
        request: VideoUploadPrepareRequest
    ): UploadPrepareResponse {
        // 지울 미디어가 남아 있어 개수 한도는 추가·수정 때 검사
        query {
            requireOwnedPost(postRepository, 
                postId = postId,
                userId = userId,
                errorMessage = Errors.Common.UNAUTHORIZED
            )
        }

        return mediaUploadService.prepare(
            userId = userId,
            purpose = UploadPurpose.POST_VIDEO,
            targetId = postId,
            mimeType = request.mimeType,
            size = request.size
        )
    }

    /** 미디어 포함 포스트 수정 */
    suspend fun updatePostWithMedia(
        postId: Int,
        userId: Int,
        content: String?,
        keepMediaIds: List<Int>?,
        newFiles: List<ByteArray>?,
        newContentTypes: List<String>?,
        reorder: List<Int>?,
        items: List<MediaItemRef>? = null
    ): PostResponse {
        val mediaAttachmentsToDelete = mutableListOf<String>()
        var prepared: List<PreparedMedia> = emptyList()

        try {
            val finalContent = content?.trim()

            finalContent?.let {
                ValidationUtils.validatePostContent(it)
            }

            val files = newFiles.orEmpty()
            val contentTypes = newContentTypes.orEmpty()
            validateImageParts(imageProcessingService, files, contentTypes, StorageConfig.FileSizeLimit.POST)
            val sources = resolveNewMediaSources(items, files.size)

            val (orderedKeepItemIds, contentToUse, deleteItemIds) = query {  // 소유자만
                val post = requireOwnedPost(postRepository, 
                    postId = postId,
                    userId = userId,
                    errorMessage = Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED
                )

                val currentItems = PostMediaDao.find { PostMediaTable.postId eq postId }
                    .orderBy(PostMediaTable.sortOrder to SortOrder.ASC)
                    .toList()

                val plan = resolveMediaEditPlan(currentItems, keepMediaIds, reorder, sources.size) { it.sortOrder }

                // 원본 + 썸네일 + 프리뷰 수거
                mediaAttachmentsToDelete.addAll(
                    plan.deleteItems.flatMap { listOfNotNull(it.url, it.thumbnailUrl, it.previewUrl) }
                )

                Triple(
                    plan.orderedKeepIds,
                    finalContent ?: post.content,
                    plan.deleteItems.map { it.id.value }
                )
            }

            val uploads = verifyVideoUploads(sources, userId, postId)
            prepared = prepareNewMedia(userId, sources, files, uploads)

            val (response, communityCreatorId) = query {
                val post = requireOwnedPost(postRepository, 
                    postId = postId,
                    userId = userId,
                    errorMessage = Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED
                )

                val extractedTags = extractHashtags(contentToUse)

                val updatedPost = postRepository.updatePost(
                    postId = postId,
                    content = contentToUse,
                    tags = extractedTags
                ) ?: throw PostUpdateFailedException(Errors.Social.Post.POST_UPDATE_FAILED)

                orderedKeepItemIds.forEachIndexed { index, mediaId ->
                    val media = PostMediaDao.findById(mediaId)
                        ?: throw InvalidInputException(Errors.Social.Media.KEEP_MEDIA_NOT_FOUND)
                    media.sortOrder = index
                }

                // 새 항목은 남길 미디어 뒤에 목록 순서대로
                prepared.forEachIndexed { index, media ->
                    media.uploadId?.let { mediaUploadService.consumeInTransaction(it) }
                    createPostMediaItem(postId, media, sortOrder = orderedKeepItemIds.size + index)
                }

                deleteItemIds.forEach { mediaId ->
                    PostMediaDao.findById(mediaId)?.delete()
                }

                val author = userRepository.findUserById(userId)?.toUserSummaryResponse()
                    ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)
                val isLiked = likeRepository.isLiked(userId, LikeType.POST, updatedPost.id.value)
                val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, updatedPost.id.value)
                val mediaItems = batchLoadPostMediaItems(listOf(updatedPost.id.value))[updatedPost.id.value] ?: emptyList()

                val postResponse = updatedPost.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
                postResponse to post.communityCreatorId
            }

            launchPostVideoTranscode(coroutineScope, videoProcessingService, fileUploadService, prepared)

            fileUploadService.deleteByUrls(mediaAttachmentsToDelete)  // 커밋 후 구 파일 삭제

            cacheService.invalidatePostCaches(postId, userId, communityCreatorId)

            return response
        } catch (e: Exception) {
            discardPreparedMedia(fileUploadService, prepared)  // 실패 시 신규 업로드분 롤백
            throw e
        }
    }

    // 공통 보조
    /** 트랜잭션 밖에서 부르는 직접 업로드 확인 */
    private suspend fun verifyVideoUploads(
        sources: List<NewMediaSource>,
        userId: Int,
        postId: Int
    ): Map<Int, MediaUploadService.VerifiedUpload> =
        sources.filterIsInstance<NewMediaSource.DirectUpload>().associate { source ->
            source.uploadId to mediaUploadService.verify(source.uploadId, userId, UploadPurpose.POST_VIDEO, postId)
        }

    /** 새 미디어 순서대로 업로드·가공 */
    private suspend fun prepareNewMedia(
        userId: Int,
        sources: List<NewMediaSource>,
        files: List<ByteArray>,
        uploads: Map<Int, MediaUploadService.VerifiedUpload>
    ): List<PreparedMedia> {
        val prepared = mutableListOf<PreparedMedia>()
        try {
            sources.forEach { source ->
                prepared += when (source) {
                    is NewMediaSource.FilePart -> prepareImage(userId, files[source.index])
                    is NewMediaSource.DirectUpload -> prepareVideo(userId, uploads.getValue(source.uploadId))
                }
            }
        } catch (e: Exception) {
            discardPreparedMedia(fileUploadService, prepared)
            throw e
        }
        return prepared
    }

    private suspend fun prepareImage(userId: Int, bytes: ByteArray): PreparedMedia {
        // 원본 업로드 뒤 실패 방지로 파생물 먼저 생성
        val dimensions = imageProcessingService.extractImageDimensions(bytes)
        val display = imageProcessingService.generateDisplayImage(bytes)
        val preview = imageProcessingService.generateLockedPreview(bytes)
        val url = fileUploadService.uploadPostMedia(userId, bytes, imageProcessingService.imageExtensionForBytes(bytes))
        return PreparedMedia(
            type = MediaType.IMAGE,
            url = url,
            width = dimensions?.first,
            height = dimensions?.second,
            durationMs = null,
            thumbnailUrl = display?.let { runCatching { fileUploadService.uploadPostThumbnail(userId, it) }.getOrNull() },
            previewUrl = preview?.let { runCatching { fileUploadService.uploadPostPreview(userId, it) }.getOrNull() }
        )
    }

    private suspend fun prepareVideo(userId: Int, upload: MediaUploadService.VerifiedUpload): PreparedMedia {
        val video = prepareUploadedVideo(videoProcessingService, fileUploadService, upload)
        val preview = video.thumbnail?.let { imageProcessingService.generateLockedPreview(it) }
        return PreparedMedia(
            type = MediaType.VIDEO,
            url = upload.url,
            width = video.meta.width,
            height = video.meta.height,
            durationMs = video.meta.durationMs,
            thumbnailUrl = video.thumbnail?.let { runCatching { fileUploadService.uploadPostThumbnail(userId, it) }.getOrNull() },
            previewUrl = preview?.let { runCatching { fileUploadService.uploadPostPreview(userId, it) }.getOrNull() },
            uploadId = upload.uploadId
        )
    }
}
