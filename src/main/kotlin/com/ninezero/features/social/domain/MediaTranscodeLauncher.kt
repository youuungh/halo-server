package com.ninezero.features.social.domain

import com.ninezero.core.common.config.MediaType
import com.ninezero.core.common.util.logger
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.VideoProcessingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val transcodeLogger = logger("MediaTranscodeLauncher")

internal fun inferMediaType(contentType: String): MediaType {
    return if (contentType.startsWith("video/", ignoreCase = true)) {
        MediaType.VIDEO
    } else {
        MediaType.IMAGE
    }
}

/** 백그라운드 트랜스코딩 */
internal fun launchVideoTranscode(
    scope: CoroutineScope,
    videoProcessingService: VideoProcessingService,
    fileUploadService: FileUploadService,
    jobs: List<Pair<String, ByteArray>>,
    mediaExists: suspend (String) -> Boolean
) {
    if (jobs.isEmpty()) return
    scope.launch {
        jobs.forEach { (url, bytes) ->
            runCatching {
                videoProcessingService.transcodeForStreaming(bytes)?.let { transcoded ->
                    // 삭제 race 가드
                    if (mediaExists(url)) {
                        fileUploadService.reuploadToUrl(url, transcoded)  // 같은 URL에 덮어씀
                        // 업로드 중 삭제 시 즉시 회수
                        if (!mediaExists(url)) fileUploadService.deleteFileIfSupabase(url)
                    }
                }
            }.onFailure { transcodeLogger.warn("백그라운드 트랜스코딩 실패 url={}: {}", url, it.message) }
        }
    }
}
