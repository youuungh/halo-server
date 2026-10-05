package com.ninezero.features.upload.presentation.models.request

import kotlinx.serialization.Serializable

/** 직접 올린 영상으로 초안 생성 요청 */
@Serializable
data class VideoDraftRequest(
    val uploadId: Int
)
