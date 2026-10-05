package com.ninezero.features.social.domain

import com.ninezero.core.common.config.MediaType
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.database.entities.social.CommentMediaDao
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.features.social.presentation.models.request.MediaItemRef
import com.ninezero.features.upload.domain.MediaDraftService
import kotlinx.serialization.json.Json

/** 새 미디어 출처 */
internal sealed interface NewMediaSource {
    data class FilePart(val index: Int) : NewMediaSource
    data class DirectUpload(val uploadId: Int) : NewMediaSource
}

/** multipart items 파트 해석 */
internal fun parseMediaItems(raw: String): List<MediaItemRef> = try {
    Json.decodeFromString(raw)
} catch (e: IllegalArgumentException) {
    throw InvalidInputException(Errors.Social.Media.INVALID_MEDIA_ITEMS)
}

/** items 순서대로 새 미디어 출처 결정 */
internal fun resolveNewMediaSources(items: List<MediaItemRef>?, fileCount: Int): List<NewMediaSource> {
    if (items == null) return List(fileCount) { NewMediaSource.FilePart(it) }

    val sources = items.map { item ->
        when {
            item.fileIndex != null && item.uploadId == null -> NewMediaSource.FilePart(item.fileIndex)
            item.uploadId != null && item.fileIndex == null -> NewMediaSource.DirectUpload(item.uploadId)
            else -> throw InvalidInputException(Errors.Social.Media.INVALID_MEDIA_ITEMS)
        }
    }

    // 파일 파트는 정확히 한 번씩, 업로드 ID는 중복 없이
    val fileIndexes = sources.filterIsInstance<NewMediaSource.FilePart>().map { it.index }
    val uploadIds = sources.filterIsInstance<NewMediaSource.DirectUpload>().map { it.uploadId }
    if (fileIndexes.sorted() != List(fileCount) { it } || uploadIds.size != uploadIds.toSet().size) {
        throw InvalidInputException(Errors.Social.Media.INVALID_MEDIA_ITEMS)
    }
    return sources
}

/** 미디어 기록 생성 전 준비물 */
internal data class PreparedMedia(
    val type: MediaType,
    val url: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val thumbnailUrl: String?,
    val previewUrl: String? = null,
    val uploadId: Int? = null
) {
    /** 실패 시 바로 지울 파일 */
    val discardableUrls: List<String>
        get() = listOfNotNull(url.takeIf { uploadId == null }, thumbnailUrl, previewUrl)  // 직접 업로드 원본은 정리 스케줄러가 삭제
}

/** 게시 트랜잭션에서 거둔 초안 값 */
internal fun MediaDraftService.DraftMedia.toPreparedMedia() = PreparedMedia(
    type = type,
    url = url,
    width = width,
    height = height,
    durationMs = durationMs,
    thumbnailUrl = thumbnailUrl,
    previewUrl = previewUrl
)

/** 실패한 업로드 정리 */
internal suspend fun discardPreparedMedia(fileUploadService: FileUploadService, prepared: List<PreparedMedia>) {
    fileUploadService.deleteByUrls(prepared.flatMap { it.discardableUrls })
}

internal fun createPostMediaItem(postId: Int, media: PreparedMedia, sortOrder: Int): PostMediaDao =
    PostMediaDao.new {
        this.postId = postId
        this.type = media.type
        this.url = media.url
        this.width = media.width
        this.height = media.height
        this.durationMs = media.durationMs
        this.thumbnailUrl = media.thumbnailUrl
        this.previewUrl = media.previewUrl
        this.sortOrder = sortOrder
    }

internal fun createCommentMediaItem(commentId: Int, media: PreparedMedia, sortOrder: Int): CommentMediaDao =
    CommentMediaDao.new {
        this.commentId = commentId
        this.type = media.type
        this.url = media.url
        this.width = media.width
        this.height = media.height
        this.durationMs = media.durationMs
        this.thumbnailUrl = media.thumbnailUrl
        this.sortOrder = sortOrder
    }

/** 미디어가 있어야 하는 글 유형 */
internal fun PostType.requiresMedia() = this == PostType.IMAGE || this == PostType.VIDEO
