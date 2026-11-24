package com.ninezero.features.chat.domain

import com.ninezero.core.common.config.ChatMessageType

/** 채팅방 목록 마지막 메시지 미리보기 */
fun createMessagePreview(
    content: String?,
    messageType: ChatMessageType,
    mediaAttachments: List<String>?
): String {
    return when {
        !content.isNullOrBlank() -> content.take(50)
        messageType == ChatMessageType.IMAGE -> {
            val count = mediaAttachments?.size ?: 1
            if (count > 1) "[이미지 ${count}장]" else "[이미지]"
        }
        messageType == ChatMessageType.VIDEO -> {
            val count = mediaAttachments?.size ?: 1
            if (count > 1) "[비디오 ${count}개]" else "[비디오]"
        }
        messageType == ChatMessageType.FILE -> {
            val count = mediaAttachments?.size ?: 1
            if (count > 1) "[파일 ${count}개]" else "[파일]"
        }
        messageType == ChatMessageType.PRODUCT_LINK -> "[상품]"
        messageType == ChatMessageType.POST_LINK -> "[포스트]"
        else -> "[메시지]"
    }
}
