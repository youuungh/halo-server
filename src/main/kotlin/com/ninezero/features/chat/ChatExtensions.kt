package com.ninezero.features.chat

import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.common.util.decodeToMediaAttachments
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.common.config.PostType
import com.ninezero.core.database.entities.chat.ChatRoomDao
import com.ninezero.core.database.entities.chat.MessageDao
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.features.social.presentation.models.response.MediaItemResponse
import com.ninezero.features.chat.presentation.models.response.ChatRoomResponse
import com.ninezero.features.chat.presentation.models.response.LastMessageInfo
import com.ninezero.features.chat.presentation.models.response.MessageResponse
import com.ninezero.features.chat.presentation.models.response.PostMessageInfo
import com.ninezero.features.chat.presentation.models.response.ProductMessageInfo
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import java.math.BigDecimal
import java.math.RoundingMode

fun ChatRoomDao.toChatRoomResponse(
    otherUser: UserSummaryResponse,
    currentUserId: Int
): ChatRoomResponse {
    val unreadCount = when (currentUserId) {
        this.user1Id -> this.user1UnreadCount
        this.user2Id -> this.user2UnreadCount
        else -> 0
    }

    val isArchived = when (currentUserId) {
        this.user1Id -> this.user1Archived
        this.user2Id -> this.user2Archived
        else -> false
    }

    val lastMessage = if (this.lastMessagePreview != null && this.lastMessageAt != null) {
        LastMessageInfo(
            preview = this.lastMessagePreview!!,
            timestamp = this.lastMessageAt!!,
            isFromMe = false // Service에서 설정 필요
        )
    } else null

    return ChatRoomResponse(
        id = this.id.value,
        otherUser = otherUser,
        lastMessage = lastMessage,
        unreadCount = unreadCount,
        isArchived = isArchived,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )
}

fun MessageDao.toMessageResponse(
    sender: UserSummaryResponse,
    currentUserId: Int,
    post: PostDao? = null,
    product: ProductDao? = null,
    postAuthor: UserSummaryResponse? = null,
    canAccess: Boolean = true,
    postCanAccess: Boolean = true,
    postMediaItems: List<MediaItemResponse>? = null,
    postHidden: Boolean = false
): MessageResponse {
    val productInfo = if (product != null) {
        val imageList = product.imageUrls.decodeJsonToList()
        val discountRate = if (product.originalPrice != null && product.originalPrice!! > BigDecimal.ZERO) {
            val discount = ((product.originalPrice!! - product.price) / product.originalPrice!!) * BigDecimal(100)
            discount.setScale(0, RoundingMode.HALF_UP).toInt()
        } else null
        ProductMessageInfo(
            productId = product.id.value,
            name = product.name,
            price = product.price.toAmountString(),
            originalPrice = product.originalPrice?.toAmountString(),
            discountRate = discountRate,
            imageUrl = imageList.firstOrNull(),
            canAccess = canAccess,
            creatorId = product.creatorId,
            creatorName = product.brandName,
            requiredTier = product.requiredTier.name
        )
    } else null

    val postInfo = if (post != null && postAuthor != null) {
        val rawMediaItems: List<MediaItemResponse> = postMediaItems ?: emptyList()
        // 잠금 글은 블러 프리뷰만
        val mediaItems = if (postCanAccess) {
            rawMediaItems
        } else {
            rawMediaItems.mapNotNull { m -> m.previewUrl?.let { p -> m.copy(url = p, thumbnailUrl = p) } }
        }
        val mediaList = mediaItems.map { it.url }
        val firstMedia = mediaItems.firstOrNull()
        val mediaType = when (post.postType) {
            PostType.IMAGE -> "IMAGE"
            PostType.VIDEO -> "VIDEO"
            else -> null
        }
        PostMessageInfo(
            postId = post.id.value,
            content = if (postCanAccess) post.content.take(100) else "",
            authorName = postAuthor.displayName ?: postAuthor.username,
            authorAvatarUrl = postAuthor.avatarUrl,
            imageUrl = mediaList.firstOrNull(),
            mediaAttachments = mediaList,
            mediaType = mediaType ?: firstMedia?.type?.name,
            thumbnailUrl = firstMedia?.thumbnailUrl,
            mediaThumbnails = mediaItems.map { it.thumbnailUrl ?: it.url },
            mediaTypes = mediaItems.map { it.type.name },
            likeCount = post.likeCount,
            commentCount = post.commentCount,
            shareCount = post.shareCount,
            canAccess = postCanAccess,
            creatorId = post.creatorId,
            requiredTier = post.requiredTier.name
        )
    } else if (postHidden && this.postId != null) {  // isHidden 최우선 분기
        // 삭제와 구분되는 placeholder
        PostMessageInfo(
            postId = this.postId!!,
            content = "",
            authorName = "",
            authorAvatarUrl = null,
            imageUrl = null,
            canAccess = false,
            isHidden = true
        )
    } else null

    // 삭제 메시지는 본문/미디어 비우고 메타만 반환
    if (this.isDeleted) {
        return MessageResponse(
            id = this.id.value,
            roomId = this.roomId,
            sender = sender,
            chatMessageType = this.chatMessageType,
            content = null,
            isRead = this.isRead,
            readAt = this.readAt,
            createdAt = this.createdAt,
            isFromMe = this.senderId == currentUserId,
            isDeleted = true
        )
    }

    return MessageResponse(
        id = this.id.value,
        roomId = this.roomId,
        sender = sender,
        chatMessageType = this.chatMessageType,
        content = this.content,
        mediaAttachments = this.mediaAttachments.decodeToMediaAttachments(),
        mediaThumbnails = this.mediaThumbnails.decodeToMediaAttachments(),
        thumbnailUrl = this.thumbnailUrl,
        duration = this.duration,
        mediaWidth = this.mediaWidth,
        mediaHeight = this.mediaHeight,
        fileSize = this.fileSize,
        fileName = this.fileName,
        postInfo = postInfo,
        productInfo = productInfo,
        isRead = this.isRead,
        readAt = this.readAt,
        createdAt = this.createdAt,
        isFromMe = this.senderId == currentUserId,
        isDeleted = this.isDeleted
    )
}
