package com.ninezero.features.chat.presentation.models.response

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class MessageResponse(
    val id: Int,
    val roomId: Int,
    val sender: UserSummaryResponse,
    val chatMessageType: ChatMessageType,
    val content: String? = null,
    val mediaAttachments: List<String>? = null,
    val mediaThumbnails: List<String>? = null,
    val thumbnailUrl: String? = null,
    val duration: Long? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val fileSize: Long? = null,
    val fileName: String? = null,
    val postInfo: PostMessageInfo? = null,
    val productInfo: ProductMessageInfo? = null,
    val isRead: Boolean,
    val readAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
    val isFromMe: Boolean,
    val isDeleted: Boolean = false
)
