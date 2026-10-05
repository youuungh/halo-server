package com.ninezero.features.upload

import com.ninezero.core.database.entities.upload.MediaDraftDao
import com.ninezero.features.upload.presentation.models.response.MediaDraftResponse

fun MediaDraftDao.toMediaDraftResponse(): MediaDraftResponse {
    return MediaDraftResponse(
        id = this.id.value,
        type = type,
        url = url,
        width = width,
        height = height,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        previewUrl = previewUrl,
        status = status,
        expiresAt = expiresAt
    )
}

/** 초안이 가진 저장소 파일 */
val MediaDraftDao.fileUrls: List<String>
    get() = listOfNotNull(url, thumbnailUrl, previewUrl)
