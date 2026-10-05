package com.ninezero.features.upload.presentation.models.request

import kotlinx.serialization.Serializable

/** 영상 직접 업로드 준비 요청 */
@Serializable
data class VideoUploadPrepareRequest(
    val mimeType: String,
    val size: Long
)
