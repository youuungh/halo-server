package com.ninezero.features.commerce.presentation.models

import kotlinx.serialization.Serializable

@Serializable
data class DetailContentBlock(
    val type: String,
    val blockId: String? = null,
    val content: String? = null,
    val url: String? = null,
    // 이미지 블록 메타데이터
    val width: Int? = null,
    val height: Int? = null,
    val textStyle: String? = null,
    val textAlign: String? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    val textColor: String? = null
)
