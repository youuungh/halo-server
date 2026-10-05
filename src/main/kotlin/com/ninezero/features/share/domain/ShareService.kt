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
import com.ninezero.features.chat.domain.MessageService
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.share.presentation.models.request.SharePostRequest
import com.ninezero.features.share.presentation.models.request.ShareProductRequest
import com.ninezero.features.share.presentation.models.response.ShareResponse
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ShareService(
    private val postRepository: PostRepository,
    private val productRepository: ProductRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val followRepository: FollowRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val notificationService: NotificationService,
    private val messageService: MessageService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    /** 수신자별 방과 이번 요청에서 새로 만든 메시지 */
    private data class ShareDelivery(
        val roomMap: Map<Int, Int>,
        val newMessages: Map<Int, RoomMessages>,
        val linkDelivered: Boolean
    ) {
        val delivered: Boolean get() = newMessages.isNotEmpty()
    }

    /** 수신자 방에 새로 만든 메시지 id */
    private data class RoomMessages(val roomId: Int, val messageIds: MutableList<Int> = mutableListOf())

    suspend fun sharePost(currentUserId: Int, request: SharePostRequest): ShareResponse {
        ValidationUtils.validateClientTempId(request.clientTempId)
        ValidationUtils.validateClientTempId(request.messageTempId)
        val sanitizedMessage = sanitizeShareMessage(request.message)

        val delivery = query {
            val post = postRepository.findPostById(request.postId)
                ?: throw NotFoundException(Errors.Social.Post.POST_NOT_FOUND)
            // 비밀글 공유 불가
            if (post.isSecret) throw InvalidInputException(Errors.Social.Post.SECRET_POST_CANNOT_SHARE)

            val result = deliverShareToRooms(
                currentUserId = currentUserId,
                recipientIds = resolveShareRecipients(currentUserId, request.recipientUserIds),  // 팔로잉 중인 사용자에게만
                sanitizedMessage = sanitizedMessage,
                linkType = ChatMessageType.POST_LINK,
                postId = request.postId,
                clientTempId = request.clientTempId,
                messageTempId = request.messageTempId
            )

            // 재전송이면 카운트를 올리지 않음
            if (result.linkDelivered) {
                postRepository.incrementShareCount(request.postId)
            }

            result
        }

        // 재전송이면 알림도 다시 보내지 않음
        if (delivery.delivered) {
            launchShareNotifications(
                currentUserId = currentUserId,
                newMessages = delivery.newMessages,
                sanitizedMessage = sanitizedMessage,
                linkType = ChatMessageType.POST_LINK,
                logContext = "postId=${request.postId}"
            )
        }

        logger.debug("포스트 공유 완료: postId={}, recipientCount={}, userId={}", request.postId, delivery.roomMap.size, currentUserId)

        return ShareResponse(
            sharedRoomIds = delivery.roomMap.values.toList(),
            recipientCount = delivery.roomMap.size
        )
    }

    suspend fun shareProduct(currentUserId: Int, request: ShareProductRequest): ShareResponse {
        ValidationUtils.validateClientTempId(request.clientTempId)
        ValidationUtils.validateClientTempId(request.messageTempId)
        val sanitizedMessage = sanitizeShareMessage(request.message)

        val delivery = query {
            // 삭제 상품은 404
            productRepository.findProductById(request.productId)
                ?: throw NotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)

            deliverShareToRooms(
                currentUserId = currentUserId,
                recipientIds = resolveShareRecipients(currentUserId, request.recipientUserIds),  // 팔로잉 중인 사용자에게만
                sanitizedMessage = sanitizedMessage,
                linkType = ChatMessageType.PRODUCT_LINK,
                productId = request.productId,
                clientTempId = request.clientTempId,
                messageTempId = request.messageTempId
            )
        }

        // 재전송이면 알림도 다시 보내지 않음
        if (delivery.delivered) {
            launchShareNotifications(
                currentUserId = currentUserId,
                newMessages = delivery.newMessages,
                sanitizedMessage = sanitizedMessage,
                linkType = ChatMessageType.PRODUCT_LINK,
                logContext = "productId=${request.productId}"
            )
        }

        logger.debug("상품 공유 완료: productId={}, recipientCount={}, userId={}", request.productId, delivery.roomMap.size, currentUserId)

        return ShareResponse(
            sharedRoomIds = delivery.roomMap.values.toList(),
            recipientCount = delivery.roomMap.size
        )
    }

    /** 수신자별 채팅방에 공유 메시지 전송 */
    private suspend fun deliverShareToRooms(
        currentUserId: Int,
        recipientIds: List<Int>,
        sanitizedMessage: String?,
        linkType: ChatMessageType,
        postId: Int? = null,
        productId: Int? = null,
        clientTempId: String? = null,
        messageTempId: String? = null
    ): ShareDelivery {
        val roomMap = mutableMapOf<Int, Int>()  // recipientId -> roomId
        val newMessages = mutableMapOf<Int, RoomMessages>()  // recipientId -> 이번에 만든 메시지
        var linkDelivered = false

        recipientIds.forEach { recipientId ->
            val room = chatRepository.getOrCreateChatRoom(currentUserId, recipientId)
            val roomId = room.id.value
            roomMap[recipientId] = roomId

            // 방·역할별로 식별자를 파생해 재전송 시 같은 메시지로 인식
            val linkTempId = clientTempId?.let { "$it#$roomId#link" }
            val alreadyLinked = linkTempId?.let {
                messageRepository.findByClientTempId(roomId, currentUserId, it)
            }

            if (alreadyLinked == null) {
                val linkMessage = messageRepository.createMessage(
                    roomId = roomId,
                    senderId = currentUserId,
                    content = null,
                    chatMessageType = linkType,
                    postId = postId,
                    productId = productId,
                    clientTempId = linkTempId
                )
                val preview = createMessagePreview(null, linkType, null)
                chatRepository.updateChatRoom(roomId, linkMessage.id.value, preview)
                chatRepository.incrementUnreadCount(roomId, recipientId)
                newMessages.getOrPut(recipientId) { RoomMessages(roomId) }.messageIds += linkMessage.id.value
                linkDelivered = true
            }

            if (sanitizedMessage != null) {
                // 카드와 따로 식별, 메시지를 고쳐 다시 보내면 새 메시지로
                val textTempId = (messageTempId ?: clientTempId)?.let { "$it#$roomId#text" }
                val alreadyTexted = textTempId?.let {
                    messageRepository.findByClientTempId(roomId, currentUserId, it)
                }

                if (alreadyTexted == null) {
                    val textMessage = messageRepository.createMessage(
                        roomId = roomId,
                        senderId = currentUserId,
                        content = sanitizedMessage,
                        chatMessageType = ChatMessageType.TEXT,
                        clientTempId = textTempId
                    )
                    val textPreview = createMessagePreview(sanitizedMessage, ChatMessageType.TEXT, null)
                    chatRepository.updateChatRoom(roomId, textMessage.id.value, textPreview)
                    chatRepository.incrementUnreadCount(roomId, recipientId)
                    newMessages.getOrPut(recipientId) { RoomMessages(roomId) }.messageIds += textMessage.id.value
                }
            }
        }
        return ShareDelivery(roomMap, newMessages, linkDelivered)
    }

    private fun launchShareNotifications(
        currentUserId: Int,
        newMessages: Map<Int, RoomMessages>,
        sanitizedMessage: String?,
        linkType: ChatMessageType,
        logContext: String
    ) {
        val preview = if (sanitizedMessage != null) {
            createMessagePreview(sanitizedMessage, ChatMessageType.TEXT, null)
        } else {
            createMessagePreview(null, linkType, null)
        }
        // 커밋 후 실시간 전달·알림 발송
        coroutineScope.launch {
            newMessages.forEach { (recipientId, room) ->
                try {
                    // 열린 채팅 화면에 실시간 반영
                    messageService.pushToReceiver(recipientId, room.messageIds)
                    notificationService.sendChatMessageNotification(recipientId, currentUserId, room.roomId, preview)
                } catch (e: Exception) {
                    logger.error("공유 알림 전송 실패: $logContext, recipientId=$recipientId, error=${e.message}", e)
                }
            }
        }
    }

    private suspend fun resolveShareRecipients(currentUserId: Int, recipientUserIds: List<Int>): List<Int> {
        ValidationUtils.validateTargetIds(recipientUserIds)

        val dedupedRecipients = recipientUserIds
            .distinct()
            .filter { it != currentUserId }

        if (dedupedRecipients.isEmpty()) {
            throw InvalidInputException(Errors.Chat.NO_VALID_SHARE_RECIPIENT)
        }

        val activeRecipientIds = userRepository.findUsersByIds(dedupedRecipients)
            .filter { it.isActive }
            .map { it.id.value }
            .toSet()

        val validRecipients = dedupedRecipients.filter { it in activeRecipientIds }
        if (validRecipients.isEmpty()) {
            throw InvalidInputException(Errors.Chat.NO_VALID_SHARE_RECIPIENT)
        }

        val followingMap = followRepository.checkMultipleFollowStatus(currentUserId, validRecipients)
        val blockRelatedUserIds = blockedUserRepository.findBlockRelatedUserIds(currentUserId)
        val followingRecipients = validRecipients
            .filter { followingMap[it] == true }
            .filter { it !in blockRelatedUserIds }

        if (followingRecipients.isEmpty()) {
            throw InvalidInputException(Errors.Chat.SHARE_FOLLOWING_ONLY)
        }

        return followingRecipients
    }

    private fun sanitizeShareMessage(message: String?): String? {
        val normalizedMessage = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        ValidationUtils.validateChatMessage(normalizedMessage)
        return normalizedMessage.trim()
    }
}
