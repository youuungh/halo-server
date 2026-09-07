package com.ninezero.features.share.domain

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.createMessagePreview
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.query
import com.ninezero.features.chat.data.ChatRepository
import com.ninezero.features.chat.data.MessageRepository
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.share.presentation.models.request.SharePostRequest
import com.ninezero.features.share.presentation.models.request.ShareProductRequest
import com.ninezero.features.share.presentation.models.response.ShareResponse
import com.ninezero.features.user.data.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ShareService(
    private val postRepository: PostRepository,
    private val productRepository: ProductRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val notificationService: NotificationService,
    private val userRepository: UserRepository,
    private val followRepository: FollowRepository,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    suspend fun sharePost(currentUserId: Int, request: SharePostRequest): ShareResponse {
        val sanitizedMessage = sanitizeShareMessage(request.message)

        val recipientRoomMap = query {
            val post = postRepository.findPostById(request.postId)
                ?: throw NotFoundException(Errors.Social.Post.POST_NOT_FOUND)
            // 비밀글 공유 불가
            if (post.isSecret) throw InvalidInputException(Errors.Social.Post.SECRET_POST_CANNOT_SHARE)

            val roomMap = deliverShareToRooms(
                currentUserId = currentUserId,
                recipientIds = resolveShareRecipients(currentUserId, request.recipientUserIds),  // 팔로잉 중인 사용자에게만
                sanitizedMessage = sanitizedMessage,
                linkType = ChatMessageType.POST_LINK,
                postId = request.postId
            )

            // shareCount + 1
            postRepository.incrementShareCount(request.postId)

            roomMap
        }

        // WebSocket 알림
        launchShareNotifications(
            currentUserId = currentUserId,
            recipientRoomMap = recipientRoomMap,
            sanitizedMessage = sanitizedMessage,
            linkType = ChatMessageType.POST_LINK,
            logContext = "postId=${request.postId}"
        )

        logger.debug("포스트 공유 완료: postId={}, recipientCount={}, userId={}", request.postId, recipientRoomMap.size, currentUserId)

        return ShareResponse(
            sharedRoomIds = recipientRoomMap.values.distinct(),
            recipientCount = recipientRoomMap.size
        )
    }

    suspend fun shareProduct(currentUserId: Int, request: ShareProductRequest): ShareResponse {
        val sanitizedMessage = sanitizeShareMessage(request.message)

        val recipientRoomMap = query {
            // 삭제 상품은 404
            productRepository.findProductById(request.productId)
                ?: throw NotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

            deliverShareToRooms(
                currentUserId = currentUserId,
                recipientIds = resolveShareRecipients(currentUserId, request.recipientUserIds),  // 팔로잉 중인 사용자에게만
                sanitizedMessage = sanitizedMessage,
                linkType = ChatMessageType.PRODUCT_LINK,
                productId = request.productId
            )
        }

        // WebSocket 알림
        launchShareNotifications(
            currentUserId = currentUserId,
            recipientRoomMap = recipientRoomMap,
            sanitizedMessage = sanitizedMessage,
            linkType = ChatMessageType.PRODUCT_LINK,
            logContext = "productId=${request.productId}"
        )

        logger.debug("상품 공유 완료: productId={}, recipientCount={}, userId={}", request.productId, recipientRoomMap.size, currentUserId)

        return ShareResponse(
            sharedRoomIds = recipientRoomMap.values.distinct(),
            recipientCount = recipientRoomMap.size
        )
    }

    /** 수신자별 채팅방에 공유 메시지 전송 */
    private suspend fun deliverShareToRooms(
        currentUserId: Int,
        recipientIds: List<Int>,
        sanitizedMessage: String?,
        linkType: ChatMessageType,
        postId: Int? = null,
        productId: Int? = null
    ): Map<Int, Int> {
        val roomMap = mutableMapOf<Int, Int>() // recipientId -> roomId
        recipientIds.forEach { recipientId ->
            val room = chatRepository.getOrCreateChatRoom(currentUserId, recipientId)
            val roomId = room.id.value
            roomMap[recipientId] = roomId

            val linkMessage = messageRepository.createMessage(
                roomId = roomId,
                senderId = currentUserId,
                content = null,
                chatMessageType = linkType,
                postId = postId,
                productId = productId
            )
            val preview = createMessagePreview(null, linkType, null)
            chatRepository.updateChatRoom(roomId, linkMessage.id.value, preview, currentUserId)
            chatRepository.incrementUnreadCount(roomId, recipientId)

            if (sanitizedMessage != null) {
                val textMessage = messageRepository.createMessage(
                    roomId = roomId,
                    senderId = currentUserId,
                    content = sanitizedMessage,
                    chatMessageType = ChatMessageType.TEXT
                )
                val textPreview = createMessagePreview(sanitizedMessage, ChatMessageType.TEXT, null)
                chatRepository.updateChatRoom(roomId, textMessage.id.value, textPreview, currentUserId)
                chatRepository.incrementUnreadCount(roomId, recipientId)
            }
        }
        return roomMap
    }

    private fun launchShareNotifications(
        currentUserId: Int,
        recipientRoomMap: Map<Int, Int>,
        sanitizedMessage: String?,
        linkType: ChatMessageType,
        logContext: String
    ) {
        val preview = if (sanitizedMessage != null) {
            createMessagePreview(sanitizedMessage, ChatMessageType.TEXT, null)
        } else {
            createMessagePreview(null, linkType, null)
        }
        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                recipientRoomMap.forEach { (recipientId, roomId) ->
                    notificationService.sendChatMessageNotification(recipientId, currentUserId, roomId, preview)
                }
            } catch (e: Exception) {
                logger.error("공유 알림 전송 실패: $logContext, error=${e.message}", e)
            }
        }
    }

    private suspend fun resolveShareRecipients(currentUserId: Int, recipientUserIds: List<Int>): List<Int> {
        ValidationUtils.validateTargetIds(recipientUserIds)

        val dedupedRecipients = recipientUserIds
            .distinct()
            .filter { it != currentUserId }

        if (dedupedRecipients.isEmpty()) {
            throw InvalidInputException("유효한 공유 대상이 없습니다.")
        }

        val activeRecipientIds = userRepository.findUsersByIds(dedupedRecipients)
            .filter { it.isActive }
            .map { it.id.value }
            .toSet()

        val validRecipients = dedupedRecipients.filter { it in activeRecipientIds }
        if (validRecipients.isEmpty()) {
            throw InvalidInputException("유효한 공유 대상이 없습니다.")
        }

        val followingMap = followRepository.checkMultipleFollowStatus(currentUserId, validRecipients)
        val followingRecipients = validRecipients.filter { followingMap[it] == true }

        if (followingRecipients.isEmpty()) {
            throw InvalidInputException("팔로잉 중인 사용자에게만 공유할 수 있습니다.")
        }

        return followingRecipients
    }

    private fun sanitizeShareMessage(message: String?): String? {
        val normalizedMessage = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        ValidationUtils.validateChatMessage(normalizedMessage, null)
        return ValidationUtils.sanitizeHtml(normalizedMessage)
    }
}
