package com.ninezero.features.chat.domain

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.PostStatus
import com.ninezero.core.common.config.UploadPurpose
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.UserBlockedException
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.chat.MessageDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.core.storage.StoragePaths
import com.ninezero.core.websocket.RealtimeFrame
import com.ninezero.core.websocket.RealtimeFrameType
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.chat.data.ChatRepository
import com.ninezero.features.chat.data.MessageRepository
import com.ninezero.features.chat.decodeToMediaAttachments
import com.ninezero.features.chat.presentation.models.request.ChatUploadPrepareRequest
import com.ninezero.features.chat.presentation.models.request.SendMessageRequest
import com.ninezero.features.chat.presentation.models.response.MarkMessagesReadResponse
import com.ninezero.features.chat.presentation.models.response.MessageDeletedEvent
import com.ninezero.features.chat.presentation.models.response.MessageListResponse
import com.ninezero.features.chat.presentation.models.response.MessageReadEvent
import com.ninezero.features.chat.presentation.models.response.MessageResponse
import com.ninezero.features.chat.presentation.models.response.SearchMessageListResponse
import com.ninezero.features.chat.toMessageResponse
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.domain.ProductService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.upload.domain.MediaUploadService
import com.ninezero.features.upload.presentation.models.response.UploadPrepareResponse
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toUserSummaryResponse
import io.ktor.serialization.kotlinx.json.DefaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MessageService(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository,
    private val postRepository: PostRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val postService: PostService,
    private val productService: ProductService,
    private val notificationService: NotificationService,
    private val mediaUploadService: MediaUploadService,
    private val webSocketManager: WebSocketManager,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    // 메시지 전송
    suspend fun sendMessage(
        roomId: Int,
        currentUserId: Int,
        request: SendMessageRequest
    ): MessageResponse {
        ValidationUtils.validateClientTempId(request.clientTempId)

        // 영상·파일은 직접 업로드한 파일로만
        val uploadPurpose = when (request.chatMessageType) {
            ChatMessageType.VIDEO -> UploadPurpose.CHAT_VIDEO
            ChatMessageType.FILE -> UploadPurpose.CHAT_FILE
            else -> null
        }
        val uploadId = uploadPurpose?.let { purpose ->
            if (!request.mediaAttachments.isNullOrEmpty()) {
                throw InvalidInputException(Errors.File.FILE_NOT_OWNED)
            }
            if (purpose == UploadPurpose.CHAT_FILE && request.fileName.isNullOrBlank()) {
                throw InvalidInputException(Errors.File.FILE_NAME_REQUIRED)
            }
            request.uploadId ?: throw InvalidInputException(Errors.File.UPLOAD_ID_REQUIRED)
        }
        // 사진 메시지는 사진 필수
        if (request.chatMessageType == ChatMessageType.IMAGE && request.mediaAttachments.isNullOrEmpty()) {
            throw InvalidInputException(Errors.File.NO_IMAGE_FILES)
        }
        ValidationUtils.validateChatMessage(request.content, request.mediaAttachments.orEmpty().size + (if (uploadId != null) 1 else 0))

        // 이 방에 본인이 올린 사진만 첨부 가능
        val attachedUrls = request.mediaAttachments.orEmpty() + request.mediaThumbnails.orEmpty() + listOfNotNull(request.thumbnailUrl)
        if (attachedUrls.any { !isOwnChatImage(it, roomId, currentUserId) }) {
            throw InvalidInputException(Errors.File.FILE_NOT_OWNED)
        }

        // 재전송은 업로드 확인 생략
        val verifiedUpload = if (uploadPurpose != null && uploadId != null) {
            val alreadySent = request.clientTempId?.let { tempId ->
                query { messageRepository.findByClientTempId(roomId, currentUserId, tempId) }
            }
            if (alreadySent == null) mediaUploadService.verify(uploadId, currentUserId, uploadPurpose, roomId) else null
        } else {
            null
        }

        val messagePreview = createMessagePreview(request.content, request.chatMessageType, request.mediaAttachments)

        val (response, receiverId, isResend) = query {
            val room = requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Chat.CHAT_PERMISSION_DENIED)

            val receiver = if (room.user1Id == currentUserId) room.user2Id else room.user1Id
            if (blockedUserRepository.isBlockedEither(currentUserId, receiver)) {
                throw UserBlockedException(Errors.User.Block.USER_BLOCKED)
            }

            // 재전송은 부수효과 없이 기존 메시지 반환
            val alreadySent = request.clientTempId?.let {
                messageRepository.findByClientTempId(roomId, currentUserId, it)
            }
            if (alreadySent != null) {
                return@query Triple(buildMessageResponses(listOf(alreadySent), currentUserId).single(), receiver, true)
            }

            request.productId?.let { productId ->
                productRepository.findProductById(productId)
                    ?: throw NotFoundException(Errors.Commerce.Product.PRODUCT_NOT_FOUND)
            }

            // 서버가 확인한 업로드 URL·크기로 저장
            if (uploadPurpose != null) {
                val upload = verifiedUpload ?: throw ConflictException(Errors.File.UPLOAD_ALREADY_USED)
                mediaUploadService.consumeInTransaction(upload.uploadId)
            }

            val content = request.content?.trim()
            val message = messageRepository.createMessage(
                roomId = roomId,
                senderId = currentUserId,
                content = content,
                chatMessageType = request.chatMessageType,
                mediaAttachments = verifiedUpload?.let { listOf(it.url) } ?: request.mediaAttachments,
                mediaThumbnails = request.mediaThumbnails,
                productId = request.productId,
                thumbnailUrl = request.thumbnailUrl,
                duration = request.duration,
                mediaWidth = request.mediaWidth,
                mediaHeight = request.mediaHeight,
                fileSize = verifiedUpload?.size ?: request.fileSize,
                fileName = request.fileName?.trim()?.take(255),  // 컬럼 길이
                clientTempId = request.clientTempId
            )

            chatRepository.updateChatRoom(roomId, message.id.value, messagePreview)
            chatRepository.incrementUnreadCount(roomId, receiver)

            Triple(buildMessageResponses(listOf(message), currentUserId).single(), receiver, false)
        }

        if (isResend) return response

        // 수신자 관점으로 보정
        webSocketManager.sendToUser(receiverId, DefaultJson.encodeToString(RealtimeFrame(RealtimeFrameType.MESSAGE, response.copy(isFromMe = false))))

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendChatMessageNotification(receiverId, currentUserId, roomId, messagePreview)
            } catch (e: Exception) {
                logger.error("채팅 알림 전송 실패: roomId=$roomId, error=${e.message}", e)
            }
        }

        return response
    }

    /** 공유 등 다른 경로로 만든 메시지를 수신자 실시간 연결로 전달 */
    suspend fun pushToReceiver(receiverId: Int, messageIds: List<Int>) {
        if (messageIds.isEmpty() || !webSocketManager.isUserConnected(receiverId)) return

        val responses = query {
            buildMessageResponses(messageIds.mapNotNull { messageRepository.findMessageById(it) }, receiverId)
        }
        responses.forEach { response ->
            webSocketManager.sendToUser(receiverId, DefaultJson.encodeToString(RealtimeFrame(RealtimeFrameType.MESSAGE, response)))
        }
    }

    // 채팅 미디어 업로드
    suspend fun uploadChatImages(
        roomId: Int,
        senderId: Int,
        images: List<ByteArray>
    ): List<Pair<String, String?>> {
        query {
            requireChatRoomParticipant(chatRepository, roomId, senderId, Errors.Chat.CHAT_PERMISSION_DENIED)
        }

        if (images.size > Constants.Chat.MAX_MEDIA_PER_MESSAGE) {
            throw InvalidInputException(Errors.File.IMAGE_LIMIT_EXCEEDED)
        }

        images.forEach { imageData ->
            if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.CHAT_IMAGE)) {
                throw InvalidInputException(Errors.File.INVALID_IMAGE_FILE)
            }
        }

        return images.map { imageData ->
            fileUploadService.uploadChatImage(roomId, senderId, imageData)
        }
    }

    /** 채팅 영상·파일 직접 업로드 발급 */
    suspend fun prepareChatUpload(
        roomId: Int,
        userId: Int,
        request: ChatUploadPrepareRequest
    ): UploadPrepareResponse {
        query {
            requireChatRoomParticipant(chatRepository, roomId, userId, Errors.Chat.CHAT_PERMISSION_DENIED)
        }

        val purpose = when (request.messageType) {
            ChatMessageType.VIDEO -> UploadPurpose.CHAT_VIDEO
            ChatMessageType.FILE -> UploadPurpose.CHAT_FILE
            else -> throw InvalidInputException(Errors.File.INVALID_MEDIA_FILE)
        }

        return mediaUploadService.prepare(
            userId = userId,
            purpose = purpose,
            targetId = roomId,
            mimeType = request.mimeType,
            size = request.size,
            originalFileName = request.fileName
        )
    }

    // 메시지 조회·읽음
    suspend fun getMessages(
        roomId: Int,
        currentUserId: Int,
        limit: Int = Constants.Chat.DEFAULT_MESSAGE_LIMIT,
        beforeMessageId: Int? = null
    ): MessageListResponse {
        val validLimit = when {
            limit <= 0 -> Constants.Chat.DEFAULT_MESSAGE_LIMIT
            limit > Constants.MAX_PAGE_LIMIT -> Constants.MAX_PAGE_LIMIT
            else -> limit
        }

        val (lastReadMessageId, messageResponses) = query {
            val room = requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Chat.CHAT_PERMISSION_DENIED)

            val lastRead = when (currentUserId) {
                room.user1Id -> room.user1LastReadMessageId
                room.user2Id -> room.user2LastReadMessageId
                else -> null
            }

            val clearedAt = when (currentUserId) {
                room.user1Id -> room.user1ClearedAt
                room.user2Id -> room.user2ClearedAt
                else -> null
            }

            val messageList = messageRepository.findMessagesByRoomId(roomId, validLimit, beforeMessageId, clearedAt)  // 클리어 시점 이후만 조회
            lastRead to buildMessageResponses(messageList, currentUserId)
        }

        val hasNext = messageResponses.size > validLimit  // 하나 더 받아 다음 여부 판단
        val actualMessages = if (hasNext) messageResponses.takeLast(validLimit) else messageResponses  // 최신 메시지 유지

        return MessageListResponse(
            messages = actualMessages,
            lastMessageId = actualMessages.lastOrNull()?.id,
            lastReadMessageId = lastReadMessageId,
            hasNext = hasNext
        )
    }

    suspend fun markMessagesAsRead(roomId: Int, currentUserId: Int): MarkMessagesReadResponse {
        val readInfo = query {
            requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Chat.CHAT_PERMISSION_DENIED)

            val info = messageRepository.markAllRead(roomId, currentUserId)
            chatRepository.resetUnreadCount(roomId, currentUserId)

            info
        }

        if (readInfo.messageIds.isNotEmpty() && readInfo.senderId != null) {
            val readEvent = MessageReadEvent(
                roomId = roomId,
                messageIds = readInfo.messageIds,
                readerId = currentUserId,
                readAt = nowUtc()
            )
            if (webSocketManager.sendToUser(readInfo.senderId, DefaultJson.encodeToString(RealtimeFrame(RealtimeFrameType.MESSAGE_READ, readEvent)))) {
                logger.debug("읽음 이벤트 전송: senderId={}, messageCount={}, roomId={}", readInfo.senderId, readInfo.messageIds.size, roomId)
            }
        }

        return MarkMessagesReadResponse(lastReadMessageId = readInfo.lastReadMessageId)
    }

    // 메시지 삭제
    suspend fun deleteMessage(messageId: Int, currentUserId: Int) {
        val (fileUrls, roomId, receiverId) = query {
            val message = messageRepository.findMessageById(messageId)
                ?: throw NotFoundException(Errors.Chat.MESSAGE_NOT_FOUND)

            if (message.senderId != currentUserId) {
                throw ForbiddenException(Errors.Chat.MESSAGE_DELETE_PERMISSION_DENIED)
            }

            // 이미 지운 메시지 재요청은 부수효과 없이 종료
            if (message.isDeleted) return@query null

            val wasUnread = !message.isRead
            messageRepository.deleteMessage(messageId)

            // 마지막 메시지면 프리뷰 갱신
            val room = requireChatRoomParticipant(chatRepository, message.roomId, currentUserId, Errors.Chat.MESSAGE_DELETE_PERMISSION_DENIED)
            if (room.lastMessageId == messageId) {
                room.lastMessagePreview = "삭제된 메시지"
            }

            val receiver = if (room.user1Id == currentUserId) room.user2Id else room.user1Id

            // 안 읽은 채 지워졌으면 수신자 안 읽음 수에서 뺌
            if (wasUnread) {
                chatRepository.decrementUnreadCount(message.roomId, receiver)
            }

            // 이 방에 본인이 올린 파일만 삭제 대상
            val ownFileUrls = (message.mediaAttachments.decodeToMediaAttachments() +
                message.mediaThumbnails.decodeToMediaAttachments() +
                listOfNotNull(message.thumbnailUrl))
                .filter { isOwnChatFile(it, message.roomId, message.senderId) }

            Triple(ownFileUrls, message.roomId, receiver)
        } ?: return

        val deletedEvent = MessageDeletedEvent(roomId = roomId, messageId = messageId)
        if (webSocketManager.sendToUser(receiverId, DefaultJson.encodeToString(RealtimeFrame(RealtimeFrameType.MESSAGE_DELETED, deletedEvent)))) {
            logger.debug("삭제 이벤트 전송: receiverId={}, messageId={}, roomId={}", receiverId, messageId, roomId)
        }

        fileUploadService.deleteByUrls(fileUrls)
    }

    // 메시지 검색
    suspend fun searchMessages(
        roomId: Int,
        currentUserId: Int,
        query: String,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): SearchMessageListResponse {
        ValidationUtils.validateMessageSearchQuery(query)

        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val room = requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Chat.CHAT_PERMISSION_DENIED)

            val clearedAt = when (currentUserId) {
                room.user1Id -> room.user1ClearedAt
                room.user2Id -> room.user2ClearedAt
                else -> null
            }

            val messages = messageRepository.searchMessages(roomId, query, validPage, validLimit, clearedAt)
            val total = messageRepository.countSearchMessages(roomId, query, clearedAt)

            val messageResponses = buildMessageResponses(messages, currentUserId)

            val pagination = PaginationInfo(validPage, validLimit, total)
            createPagedResponse(messageResponses, pagination)
        }
    }

    // 공통 보조
    /** 보낸 사람이 이 방에 올린 채팅 사진 판정 */
    private fun isOwnChatImage(url: String, roomId: Int, senderId: Int): Boolean {
        val room = "chats/$roomId/"
        return StoragePaths.isOwned(url, StorageConfig.Buckets.CHAT_IMAGES, "${room}chat_${senderId}_") ||
            StoragePaths.isOwned(url, StorageConfig.Buckets.CHAT_IMAGES, "${room}chat_thumb_${senderId}_")
    }

    /** 보낸 사람이 이 방에 올린 채팅 파일 판정 */
    private fun isOwnChatFile(url: String, roomId: Int, senderId: Int): Boolean {
        val room = "chats/$roomId/"
        return isOwnChatImage(url, roomId, senderId) ||
            StoragePaths.isOwned(url, StorageConfig.Buckets.CHAT_VIDEOS, "${room}videos/chat_video_${senderId}_") ||
            StoragePaths.isOwned(url, StorageConfig.Buckets.CHAT_FILES, "${room}files/chat_file_${senderId}_")
    }

    private suspend fun buildMessageResponses(
        messages: List<MessageDao>,
        currentUserId: Int
    ): List<MessageResponse> {
        if (messages.isEmpty()) return emptyList()

        val senderIds = messages.map { it.senderId }.toSet()
        val userMap = userRepository.findUsersByIds(senderIds)
            .associateBy { it.id.value }

        val productIds = messages.mapNotNull { it.productId }.toSet()
        val productMap = if (productIds.isNotEmpty()) {
            productRepository.findProductsByIds(productIds)
                .associateBy { it.id.value }
        } else emptyMap()
        val accessMap = productService.getProductAccessMap(currentUserId, productMap.values)
        val blockRelatedUserIds = blockedUserRepository.findBlockRelatedUserIds(currentUserId)

        val postIds = messages.mapNotNull { it.postId }.toSet()
        val postMap = if (postIds.isNotEmpty()) {
            postRepository.findPostsByIds(postIds)
                .associateBy { it.id.value }
        } else emptyMap()
        // HIDDEN 글 구분
        val missingPostIds = postIds - postMap.keys
        val hiddenPostIds = if (missingPostIds.isNotEmpty()) {
            postRepository.findPostsByIdsWithDeleted(missingPostIds)
                .filter { !it.isActive && it.status == PostStatus.HIDDEN }
                .map { it.id.value }
                .toSet()
        } else emptySet()
        val postAccessMap = if (postMap.isNotEmpty()) {
            postService.checkMultiplePostAccess(currentUserId, postMap.values.toList())
        } else emptyMap()

        val postAuthorIds = postMap.values.map { it.userId }.toSet()
        val postAuthorMap = if (postAuthorIds.isNotEmpty()) {
            userRepository.findUsersByIds(postAuthorIds)
                .associateBy { it.id.value }
        } else emptyMap()

        val postMediaItemsMap = batchLoadPostMediaItems(postIds.toList())

        return messages.map { message ->
            val sender = userMap[message.senderId]?.toUserSummaryResponse()
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            val product = message.productId?.let { productMap[it] }
            val canAccess = product?.let {
                (accessMap[it.id.value] ?: false) && (it.creatorId !in blockRelatedUserIds)
            } ?: true
            val post = message.postId?.let { postMap[it] }
            val postCanAccess = post?.let {
                (postAccessMap[it.id.value] ?: false) && (it.userId !in blockRelatedUserIds)
            } ?: true
            val postAuthor = post?.let { postAuthorMap[it.userId]?.toUserSummaryResponse() }
            message.toMessageResponse(
                sender = sender,
                currentUserId = currentUserId,
                post = post,
                product = product,
                postAuthor = postAuthor,
                canAccess = canAccess,
                postCanAccess = postCanAccess,
                postMediaItems = post?.let { postMediaItemsMap[it.id.value] ?: emptyList() },
                postHidden = message.postId in hiddenPostIds
            )
        }
    }
}
