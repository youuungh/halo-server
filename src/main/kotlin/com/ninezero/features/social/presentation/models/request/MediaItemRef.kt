package com.ninezero.features.social.presentation.models.request

import kotlinx.serialization.Serializable

/** 새 미디어 순서 항목 */
@Serializable
data class MediaItemRef(
    val fileIndex: Int? = null,  // uploadId와 둘 중 하나만
    val uploadId: Int? = null
)
