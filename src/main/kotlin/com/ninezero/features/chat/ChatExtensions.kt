package com.ninezero.features.chat

import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.database.entities.chat.ChatRoomDao
import com.ninezero.core.database.entities.chat.MessageDao
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.features.chat.presentation.models.response.ChatRoomResponse
import com.ninezero.features.chat.presentation.models.response.LastMessageInfo
import com.ninezero.features.chat.presentation.models.response.MessageResponse
import com.ninezero.features.chat.presentation.models.response.PostMessageInfo
import com.ninezero.features.chat.presentation.models.response.ProductMessageInfo
import com.ninezero.features.commerce.dealPricingAt
import com.ninezero.features.social.presentation.models.response.MediaItemResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.serialization.json.Json

fun ChatRoomDao.toChatRoomResponse(
    otherUser: UserSummaryResponse,
    currentUserId: Int,
    isBlockedByMe: Boolean,
    isBlockedByOther: Boolean
): ChatRoomResponse {
    val unreadCount = when (currentUserId) {
        user1Id -> user1UnreadCount
        user2Id -> user2UnreadCount
        else -> 0
    }

    val isArchived = when (currentUserId) {
        user1Id -> user1Archived
        user2Id -> user2Archived
        else -> false
    }

    val preview = lastMessagePreview
    val timestamp = lastMessageAt
    val lastMessage = if (preview != null && timestamp != null) {
        LastMessageInfo(
            preview = preview,
            timestamp = timestamp
        )
    } else null

    return ChatRoomResponse(
        id = id.value,
        otherUser = otherUser,
        lastMessage = lastMessage,
        unreadCount = unreadCount,
        isArchived = isArchived,
        isBlockedByMe = isBlockedByMe,
        isBlockedByOther = isBlockedByOther,
        createdAt = createdAt,
        updatedAt = updatedAt
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
    // 삭제 메시지는 본문/미디어 비우고 메타만 반환
    if (isDeleted) {
        return MessageResponse(
            id = id.value,
            roomId = roomId,
            sender = sender,
            chatMessageType = chatMessageType,
            content = null,
            isRead = isRead,
            readAt = readAt,
            createdAt = createdAt,
            isFromMe = senderId == currentUserId,
            isDeleted = true
        )
    }

    val productInfo = if (product != null) {
        val imageList = product.imageUrls.decodeJsonToList()
        val pricing = product.dealPricingAt()  // 상품 상세·주문과 같은 타임딜 판정
        ProductMessageInfo(
            productId = product.id.value,
            name = product.name,
            price = pricing.price.toAmountString(),
            originalPrice = pricing.originalPrice?.toAmountString(),
            discountRate = pricing.discountRate,
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
    } else if (postHidden) {  // isHidden 최우선 분기
        // 삭제와 구분되는 placeholder
        postId?.let { hiddenPostId ->
            PostMessageInfo(
                postId = hiddenPostId,
                content = "",
                authorName = "",
                authorAvatarUrl = null,
                imageUrl = null,
                canAccess = false,
                isHidden = true
            )
        }
    } else null

    return MessageResponse(
        id = id.value,
        roomId = roomId,
        sender = sender,
        chatMessageType = chatMessageType,
        content = content,
        mediaAttachments = mediaAttachments.decodeToMediaAttachments(),
        mediaThumbnails = mediaThumbnails.decodeToMediaAttachments(),
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        mediaWidth = mediaWidth,
        mediaHeight = mediaHeight,
        fileSize = fileSize,
        fileName = fileName,
        postInfo = postInfo,
        productInfo = productInfo,
        isRead = isRead,
        readAt = readAt,
        createdAt = createdAt,
        isFromMe = senderId == currentUserId
    )
}

fun List<String>.encodeToMediaAttachments(): String? {
    return if (isNotEmpty()) Json.encodeToString(this) else null
}

fun String?.decodeToMediaAttachments(): List<String> {
    if (isNullOrBlank()) return emptyList()

    return try {
        Json.decodeFromString<List<String>>(this)
    } catch (_: Exception) {
        emptyList()
    }
}
