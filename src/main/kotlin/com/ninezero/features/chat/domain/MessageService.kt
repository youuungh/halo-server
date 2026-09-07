package com.ninezero.features.chat.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.PostStatus
import com.ninezero.core.database.entities.chat.ChatRoomDao
import com.ninezero.core.database.entities.chat.MessageDao
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.UserBlockedException
import com.ninezero.core.common.util.*
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.chat.data.ChatRepository
import com.ninezero.features.chat.data.MessageRepository
import com.ninezero.features.chat.presentation.models.request.SendMessageRequest
import kotlinx.serialization.json.Json
import com.ninezero.features.chat.presentation.models.response.MarkMessagesReadResponse
import com.ninezero.features.chat.presentation.models.response.MessageDeletedEvent
import com.ninezero.features.chat.presentation.models.response.MessageListResponse
import com.ninezero.features.chat.presentation.models.response.MessageReadEvent
import com.ninezero.features.chat.presentation.models.response.MessageResponse
import com.ninezero.features.chat.presentation.models.response.SearchMessageListResponse
import com.ninezero.features.chat.toMessageResponse
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.domain.ProductService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.core.websocket.RealtimeFrame
import com.ninezero.core.websocket.RealtimeFrameType
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse
import io.ktor.server.plugins.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

class MessageService(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository,
    private val postRepository: PostRepository,
    private val postService: PostService,
    private val blockedUserRepository: BlockedUserRepository,
    private val productService: ProductService,
    private val notificationService: NotificationService,
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
        ValidationUtils.validateChatMessage(request.content, request.mediaAttachments)

        val messagePreview = createMessagePreview(request.content, request.chatMessageType, request.mediaAttachments)

        val (response, receiverId) = query {
            val room = requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Common.PERMISSION_DENIED)

            val otherUserId = if (room.user1Id == currentUserId) room.user2Id else room.user1Id
            if (blockedUserRepository.isBlockedEither(currentUserId, otherUserId)) {
                throw UserBlockedException(Errors.User.Block.USER_BLOCKED)
            }

            val content = request.content?.let { ValidationUtils.sanitizeHtml(it) }
            val message = messageRepository.createMessage(
                roomId = roomId,
                senderId = currentUserId,
                content = content,
                chatMessageType = request.chatMessageType,
                mediaAttachments = request.mediaAttachments,
                mediaThumbnails = request.mediaThumbnails,
                productId = request.productId,
                thumbnailUrl = request.thumbnailUrl,
                duration = request.duration,
                mediaWidth = request.mediaWidth,
                mediaHeight = request.mediaHeight,
                fileSize = request.fileSize,
                fileName = request.fileName
            )

            chatRepository.updateChatRoom(roomId, message.id.value, messagePreview, currentUserId)

            val receiver = if (room.user1Id == currentUserId) room.user2Id else room.user1Id
            chatRepository.incrementUnreadCount(roomId, receiver)

            val sender = userRepository.findUserById(currentUserId)
                ?: throw NotFoundException(Errors.User.USER_INFO_NOT_FOUND)

            val product = request.productId?.let { productRepository.findProductById(it) }
            val canAccess = product?.let {
                productService.getProductAccessMap(currentUserId, listOf(it))[it.id.value] ?: false
            } ?: true

            val messageResponse = message.toMessageResponse(
                sender = sender.toSummaryResponse(),
                currentUserId = currentUserId,
                product = product,
                canAccess = canAccess
            )

            Pair(messageResponse, receiver)
        }

        if (webSocketManager.isUserConnected(receiverId)) {
            // 수신자 관점으로 보정
            webSocketManager.sendToUser(receiverId, Json.encodeToString(RealtimeFrame(RealtimeFrameType.MESSAGE, response.copy(isFromMe = false))))
        }

        // 알림 전송
        coroutineScope.launch {
            try {
                notificationService.sendChatMessageNotification(receiverId, currentUserId, roomId, messagePreview)
            } catch (e: Exception) {
                logger.error("채팅 알림 전송 실패: roomId=$roomId, error=${e.message}", e)
            }
        }

        return response
    }

    // 채팅 미디어 업로드
    suspend fun uploadChatImages(
        roomId: Int,
        senderId: Int,
        images: List<ByteArray>,
        contentTypes: List<String>
    ): List<Pair<String, String?>> {
        query {
            requireChatRoomParticipant(chatRepository, roomId, senderId, Errors.Chat.CHAT_PERMISSION_DENIED)
        }

        if (images.size > Constants.Chat.MAX_MEDIA_PER_MESSAGE) {
            throw BadRequestException(Errors.File.IMAGE_LIMIT_EXCEEDED)
        }

        images.forEach { imageData ->
            if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.CHAT_IMAGE)) {
                throw BadRequestException(Errors.File.INVALID_IMAGE_FILE)
            }
        }

        val extensions = contentTypes.map {
            imageProcessingService.getFileExtension(it)
        }

        return images.mapIndexed { index, imageData ->
            fileUploadService.uploadChatImage(roomId, senderId, imageData, extensions[index])
        }
    }

    suspend fun uploadChatVideo(
        roomId: Int,
        senderId: Int,
        videoData: ByteArray,
        contentType: String
    ): String {
        query {
            requireChatRoomParticipant(chatRepository, roomId, senderId, Errors.Chat.CHAT_PERMISSION_DENIED)
        }

        if (videoData.size > StorageConfig.FileSizeLimit.CHAT_VIDEO) {
            throw BadRequestException(Errors.File.VIDEO_SIZE_EXCEEDED)
        }

        if (contentType !in StorageConfig.AllowedMimeTypes.VIDEOS) {
            throw BadRequestException(Errors.File.VIDEO_UNSUPPORTED_FORMAT)
        }

        val extension = when (contentType) {
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/x-msvideo" -> "avi"
            else -> "mp4"
        }

        return fileUploadService.uploadChatVideo(roomId, senderId, videoData, extension)
    }

    suspend fun uploadChatFile(
        roomId: Int,
        senderId: Int,
        fileData: ByteArray,
        originalFileName: String
    ): String {
        query {
            requireChatRoomParticipant(chatRepository, roomId, senderId, Errors.Chat.CHAT_PERMISSION_DENIED)
        }

        // 용량 검증
        if (fileData.size > StorageConfig.FileSizeLimit.CHAT_FILE) {
            throw BadRequestException(Errors.File.FILE_SIZE_EXCEEDED)
        }

        return fileUploadService.uploadChatFile(roomId, senderId, fileData, originalFileName)
    }

    // 메시지 조회·읽음
    suspend fun getMessages(
        roomId: Int,
        currentUserId: Int,
        limit: Int = Constants.Chat.DEFAULT_MESSAGE_LIMIT,
        beforeMessageId: Int? = null
    ): MessageListResponse {
        var lastReadMessageId: Int? = null
        val validLimit = when {
            limit <= 0 -> Constants.Chat.DEFAULT_MESSAGE_LIMIT
            limit > Constants.MAX_PAGE_LIMIT -> Constants.MAX_PAGE_LIMIT
            else -> limit
        }

        val messageResponses = query {
            val room = requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Common.PERMISSION_DENIED)

            lastReadMessageId = when (currentUserId) {
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

            if (messageList.isEmpty()) {
                return@query emptyList()
            }

            buildMessageResponses(messageList, currentUserId)
        }

        val hasNext = messageResponses.size > validLimit  // 오버플로 1건으로 다음 페이지 판단
        val actualMessages = if (hasNext) {
            // takeLast로 최신 메시지 유지
            messageResponses.takeLast(validLimit)
        } else {
            messageResponses
        }

        return MessageListResponse(
            messages = actualMessages,
            lastMessageId = actualMessages.lastOrNull()?.id,
            lastReadMessageId = lastReadMessageId,
            hasNext = hasNext
        )
    }

    suspend fun markMessagesAsRead(roomId: Int, currentUserId: Int): MarkMessagesReadResponse {
        val readInfo = query {
            requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Common.PERMISSION_DENIED)

            val info = messageRepository.markAllRead(roomId, currentUserId)
            chatRepository.resetUnreadCount(roomId, currentUserId)

            info
        }

        // 방의 알림 행도 함께 읽음 처리
        runCatching { notificationService.markChatRoomNotificationsAsRead(currentUserId, roomId) }
            .onFailure { logger.warn("채팅 알림 읽음 처리 실패: roomId={}, error={}", roomId, it.message) }

        if (readInfo.messageIds.isNotEmpty() && readInfo.senderId != null && webSocketManager.isUserConnected(readInfo.senderId)) {
                val readEvent = MessageReadEvent(
                    roomId = roomId,
                    messageIds = readInfo.messageIds,
                    readerId = currentUserId,
                    readAt = Clock.System.now().toLocalDateTime(TimeZone.UTC).toString()
                )
                webSocketManager.sendToUser(readInfo.senderId, Json.encodeToString(RealtimeFrame(RealtimeFrameType.MESSAGE_READ, readEvent)))
                logger.debug("읽음 이벤트 전송: senderId={}, messageCount={}, roomId={}", readInfo.senderId, readInfo.messageIds.size, roomId)
        }

        return MarkMessagesReadResponse(lastReadMessageId = readInfo.lastReadMessageId)
    }

    // 메시지 삭제
    suspend fun deleteMessage(messageId: Int, currentUserId: Int) {
        val (mediaAttachments, roomId, receiverId) = query {
            val message = messageRepository.findMessageById(messageId)
                ?: throw NotFoundException(Errors.Chat.MESSAGE_NOT_FOUND)

            if (message.senderId != currentUserId) {
                throw ForbiddenException(Errors.Chat.MESSAGE_DELETE_PERMISSION_DENIED)
            }

            messageRepository.deleteMessage(messageId)

            // 마지막 메시지면 프리뷰 갱신
            val room = ChatRoomDao.findById(message.roomId)
            if (room != null && room.lastMessageId == messageId) {
                room.lastMessagePreview = "삭제된 메시지"
            }

            val receiver = room?.let { if (it.user1Id == currentUserId) it.user2Id else it.user1Id }

            // 미디어 + 썸네일 반환
            Triple(
                message.mediaAttachments.decodeToMediaAttachments() + listOfNotNull(message.thumbnailUrl),
                message.roomId,
                receiver
            )
        }

        if (receiverId != null && webSocketManager.isUserConnected(receiverId)) {
            val deletedEvent = MessageDeletedEvent(roomId = roomId, messageId = messageId)
            webSocketManager.sendToUser(receiverId, Json.encodeToString(RealtimeFrame(RealtimeFrameType.MESSAGE_DELETED, deletedEvent)))
            logger.debug("삭제 이벤트 전송: receiverId={}, messageId={}, roomId={}", receiverId, messageId, roomId)
        }

        mediaAttachments.forEach { mediaUrl ->
            try {
                fileUploadService.deleteFileIfSupabase(mediaUrl)
            } catch (e: Exception) {
                logger.warn("미디어 파일 삭제 실패: {} - {}", mediaUrl, e.message)
            }
        }
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
            val room = requireChatRoomParticipant(chatRepository, roomId, currentUserId, Errors.Common.PERMISSION_DENIED)

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
    private suspend fun buildMessageResponses(
        messages: List<MessageDao>,
        currentUserId: Int
    ): List<MessageResponse> {
        if (messages.isEmpty()) return emptyList()

        val senderIds = messages.map { it.senderId }.toSet()
        val userMap = userRepository.findUsersByIds(senderIds.toList())
            .associateBy { it.id.value }

        val productIds = messages.mapNotNull { it.productId }.toSet()
        val productMap = if (productIds.isNotEmpty()) {
            productRepository.findProductsByIds(productIds.toList())
                .associateBy { it.id.value }
        } else emptyMap()
        val accessMap = productService.getProductAccessMap(currentUserId, productMap.values)
        val blockRelatedUserIds = blockedUserRepository.findBlockRelatedUserIds(currentUserId)

        val postIds = messages.mapNotNull { it.postId }.toSet()
        val postMap = if (postIds.isNotEmpty()) {
            postRepository.findPostsByIds(postIds.toList())
                .associateBy { it.id.value }
        } else emptyMap()
        // HIDDEN 글 구분
        val missingPostIds = postIds - postMap.keys
        val hiddenPostIds = if (missingPostIds.isNotEmpty()) {
            postRepository.findPostsByIdsWithDeleted(missingPostIds.toList())
                .filter { !it.isActive && it.status == PostStatus.HIDDEN }
                .map { it.id.value }
                .toSet()
        } else emptySet()
        val postAccessMap = if (postMap.isNotEmpty()) {
            postService.checkMultiplePostAccess(currentUserId, postMap.values.toList())
        } else emptyMap()

        val postAuthorIds = postMap.values.map { it.userId }.toSet()
        val postAuthorMap = if (postAuthorIds.isNotEmpty()) {
            userRepository.findUsersByIds(postAuthorIds.toList())
                .associateBy { it.id.value }
        } else emptyMap()

        val postMediaItemsMap = batchLoadPostMediaItems(postIds.toList())

        return messages.map { message ->
            val sender = userMap[message.senderId]?.toSummaryResponse()
                ?: throw NotFoundException(Errors.User.USER_NOT_FOUND)

            val product = message.productId?.let { productMap[it] }
            val canAccess = product?.let {
                (accessMap[it.id.value] ?: false) && (it.creatorId !in blockRelatedUserIds)
            } ?: true
            val post = message.postId?.let { postMap[it] }
            val postCanAccess = post?.let {
                (postAccessMap[it.id.value] ?: false) && (it.userId !in blockRelatedUserIds)
            } ?: true
            val postAuthor = post?.let { postAuthorMap[it.userId]?.toSummaryResponse() }
            message.toMessageResponse(
                sender = sender,
                currentUserId = currentUserId,
                post = post,
                product = product,
                postAuthor = postAuthor,
                canAccess = canAccess,
                postCanAccess = postCanAccess,
                postMediaItems = post?.let { postMediaItemsMap[it.id.value] ?: emptyList() },
                postHidden = message.postId?.let { it in hiddenPostIds } ?: false
            )
        }
    }
}
