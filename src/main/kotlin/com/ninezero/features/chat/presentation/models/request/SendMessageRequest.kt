package com.ninezero.features.chat.presentation.models.request

import com.ninezero.core.common.config.ChatMessageType
import kotlinx.serialization.Serializable

@Serializable
data class SendMessageRequest(
    val content: String? = null,
    val chatMessageType: ChatMessageType = ChatMessageType.TEXT,
    val mediaAttachments: List<String>? = null,
    val mediaThumbnails: List<String>? = null,
    val productId: Int? = null,
    val thumbnailUrl: String? = null,
    val duration: Long? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val fileSize: Long? = null,
    val fileName: String? = null,
    // 재전송 중복 방지용 클라이언트 생성 식별자
    val clientTempId: String? = null,
    // 직접 업로드한 영상·파일
    val uploadId: Int? = null
)
