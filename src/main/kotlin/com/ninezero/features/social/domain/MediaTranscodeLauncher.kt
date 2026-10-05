package com.ninezero.features.social.domain

import com.ninezero.core.common.config.MediaType
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.CommentMediaDao
import com.ninezero.core.database.entities.social.CommentMediaTable
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.VideoProcessingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val transcodeLogger = logger("MediaTranscodeLauncher")

/** 포스트 영상 백그라운드 트랜스코딩 */
internal fun launchPostVideoTranscode(
    scope: CoroutineScope,
    videoProcessingService: VideoProcessingService,
    fileUploadService: FileUploadService,
    media: List<PreparedMedia>
) = launchVideoTranscode(scope, videoProcessingService, fileUploadService, media) { url ->
    query { !PostMediaDao.find { PostMediaTable.url eq url }.empty() }
}

/** 댓글 영상 백그라운드 트랜스코딩 */
internal fun launchCommentVideoTranscode(
    scope: CoroutineScope,
    videoProcessingService: VideoProcessingService,
    fileUploadService: FileUploadService,
    media: List<PreparedMedia>
) = launchVideoTranscode(scope, videoProcessingService, fileUploadService, media) { url ->
    query { !CommentMediaDao.find { CommentMediaTable.url eq url }.empty() }
}

/** 게시된 영상을 저장소에서 다시 받아 재인코딩 */
private fun launchVideoTranscode(
    scope: CoroutineScope,
    videoProcessingService: VideoProcessingService,
    fileUploadService: FileUploadService,
    media: List<PreparedMedia>,
    mediaExists: suspend (String) -> Boolean
) {
    val urls = media.filter { it.type == MediaType.VIDEO }.map { it.url }
    if (urls.isEmpty()) return
    scope.launch {
        urls.forEach { url ->
            try {
                val source = fileUploadService.downloadToTempFile(url)
                try {
                    videoProcessingService.transcodeForStreaming(source)?.let { transcoded ->
                        // 삭제 race 가드
                        if (mediaExists(url)) {
                            fileUploadService.reuploadToUrl(url, transcoded)  // 같은 URL에 덮어씀
                            // 업로드 중 삭제 시 즉시 회수
                            if (!mediaExists(url)) fileUploadService.deleteByUrl(url)
                        }
                    }
                } finally {
                    source.delete()
                }
            } catch (e: Exception) {
                transcodeLogger.warn("백그라운드 트랜스코딩 실패 url={}: {}", url, e.message)
            }
        }
    }
}
