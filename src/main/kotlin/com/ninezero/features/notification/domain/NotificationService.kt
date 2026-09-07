package com.ninezero.features.notification.domain

import com.ninezero.core.common.config.NotificationCategory
import com.ninezero.core.websocket.RealtimeFrame
import com.ninezero.core.websocket.RealtimeFrameType
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.core.common.config.NotificationTargetType
import com.ninezero.core.common.config.NotificationType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.common.config.getCategory
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import kotlinx.serialization.json.Json
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.core.database.entities.notification.NotificationDao
import com.ninezero.core.database.entities.notification.NotificationPreferenceDao
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.fcm.FcmService
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.notification.data.NotificationData
import com.ninezero.features.notification.data.NotificationPreferenceRepository
import com.ninezero.features.notification.data.NotificationRepository
import com.ninezero.features.notification.presentation.models.response.NotificationListResponse
import com.ninezero.features.notification.presentation.models.response.NotificationResponse
import com.ninezero.features.notification.presentation.models.response.UnreadCountResponse
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.notification.toNotificationResponse
import com.ninezero.features.user.data.UserRepository
import java.math.BigDecimal
import kotlinx.datetime.*
import org.jetbrains.exposed.sql.SortOrder

class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    private val webSocketManager: WebSocketManager,
    private val fcmService: FcmService,
    private val preferenceRepository: NotificationPreferenceRepository,
    private val postRepository: PostRepository,
    private val productRepository: ProductRepository
) {
    private data class SenderInfo(
        val username: String,
        val avatarUrl: String?
    )

    // 알림 생성 공통
    private suspend fun requireSenderInfo(userId: Int, errorMessage: String): SenderInfo = query {
        val user = userRepository.findUserById(userId)
            ?: throw NotFoundException(errorMessage)

        SenderInfo(
            username = user.username,
            avatarUrl = user.profile?.avatarUrl
        )
    }

    private suspend fun findSenderInfo(userId: Int): SenderInfo? = query {
        userRepository.findUserById(userId)?.let { user ->
            SenderInfo(
                username = user.username,
                avatarUrl = user.profile?.avatarUrl
            )
        }
    }

    /** 푸시 전송 여부 판정 */
    private suspend fun shouldSendPush(recipientId: Int, type: NotificationType): Boolean {
        return query { allowsPush(preferenceRepository.findByUserId(recipientId), type) }
    }

    private fun allowsPush(preference: NotificationPreferenceDao?, type: NotificationType): Boolean {
        if (preference == null) return true  // 설정 없으면 기본 전송
        return when (type.getCategory()) {
            NotificationCategory.SOCIAL -> preference.socialEnabled
            NotificationCategory.CREATOR_ACTIVITY -> preference.creatorActivityEnabled
            NotificationCategory.COMMERCE -> preference.commerceEnabled
            NotificationCategory.CHAT -> preference.chatEnabled
            NotificationCategory.SYSTEM -> preference.systemEnabled
        }
    }

    private suspend fun createAndSendNotification(
        recipientId: Int,
        senderId: Int?,
        type: NotificationType,
        title: String,
        message: String,
        targetType: String?,
        targetId: Int?,
        senderUsername: String? = null,
        senderAvatarUrl: String? = null,
        imageUrl: String? = null,
        deepLink: String? = null,
        // 채팅 전용 순수 메시지 텍스트
        messagePreview: String? = null
    ): NotificationResponse {
        val metadata = buildDedupKey(
            recipientId = recipientId,
            senderId = senderId,
            type = type,
            title = title,
            message = message,
            targetType = targetType,
            targetId = targetId,
            deepLink = deepLink
        ).takeIf { needsDedup(type) }

        val notificationResult: Pair<NotificationDao, Boolean> = query {
            val existingNotification = if (metadata != null) {
                notificationRepository.findActiveByMetadata(metadata)
            } else {
                null
            }
            if (existingNotification != null) {
                existingNotification to false
            } else {
                notificationRepository.createNotification(
                    recipientId = recipientId,
                    senderId = senderId,
                    type = type,
                    title = title,
                    message = message,
                    targetType = targetType,
                    targetId = targetId,
                    imageUrl = imageUrl,
                    deepLink = deepLink,
                    metadata = metadata
                ) to true
            }
        }
        val (notification, isNewNotification) = notificationResult

        val response = notification.toNotificationResponse(senderUsername, senderAvatarUrl)

        if (isNewNotification && shouldSendPush(recipientId, type)) {
            val deliveredByWebSocket = webSocketManager.sendToUser(
                recipientId,
                Json.encodeToString(
                    RealtimeFrame(
                        type = RealtimeFrameType.NOTIFICATION,
                        data = response
                    )
                )
            )

            if (!deliveredByWebSocket) {
                sendFcmFallback(
                    recipientId = recipientId,
                    notificationId = notification.id.value,
                    type = type,
                    title = title,
                    message = message,
                    targetType = targetType,
                    targetId = targetId,
                    senderAvatarUrl = senderAvatarUrl,
                    imageUrl = imageUrl,
                    deepLink = deepLink,
                    // 채팅 그룹 요약 제목에 쓰는 값
                    senderUsername = senderUsername,
                    messagePreview = messagePreview
                )
            }
        }

        return response
    }

    // 소셜 알림
    suspend fun sendFollowNotification(followerId: Int, followedId: Int): NotificationResponse {
        val follower = requireSenderInfo(followerId, Errors.Social.Follow.FOLLOWER_NOT_FOUND)

        return createAndSendNotification(
            recipientId = followedId,
            senderId = followerId,
            type = NotificationType.FOLLOW,
            title = "새로운 팔로워",
            message = "${follower.username}님이 회원님을 팔로우하기 시작했습니다",
            targetType = NotificationTargetType.USER,
            targetId = followerId,
            senderUsername = follower.username,
            senderAvatarUrl = follower.avatarUrl,
            deepLink = "/users/$followerId"  // 대상 사용자 페이지로 딥링크
        )
    }

    suspend fun sendLikePostNotification(
        postOwnerId: Int,
        likerId: Int,
        postId: Int
    ): NotificationResponse? {
        if (postOwnerId == likerId) {
            return null  // 자신의 포스트는 알림 스킵
        }

        val liker = requireSenderInfo(likerId, Errors.User.USER_INFO_NOT_FOUND)
        val imageUrl = getPostImageUrl(postId)

        return createAndSendNotification(
            recipientId = postOwnerId,
            senderId = likerId,
            type = NotificationType.LIKE_POST,
            title = "포스트 좋아요",
            message = "${liker.username}님이 회원님의 포스트를 좋아합니다",
            targetType = NotificationTargetType.POST,
            targetId = postId,
            senderUsername = liker.username,
            senderAvatarUrl = liker.avatarUrl,
            imageUrl = imageUrl,
            deepLink = "/posts/$postId"
        )
    }

    suspend fun sendLikeCommentNotification(
        commentOwnerId: Int,
        likerId: Int,
        commentId: Int,
        postId: Int
    ): NotificationResponse? {
        if (commentOwnerId == likerId) {
            return null // 자신의 댓글은 알림 스킵
        }

        val liker = requireSenderInfo(likerId, Errors.User.USER_INFO_NOT_FOUND)
        val imageUrl = getPostImageUrl(postId)

        return createAndSendNotification(
            recipientId = commentOwnerId,
            senderId = likerId,
            type = NotificationType.LIKE_COMMENT,
            title = "댓글 좋아요",
            message = "${liker.username}님이 회원님의 댓글을 좋아합니다",
            targetType = NotificationTargetType.COMMENT,
            targetId = commentId,
            senderUsername = liker.username,
            senderAvatarUrl = liker.avatarUrl,
            imageUrl = imageUrl,
            deepLink = "/posts/$postId#comment-$commentId"
        )
    }

    suspend fun sendCommentNotification(
        postOwnerId: Int,
        commenterId: Int,
        postId: Int,
        commentId: Int
    ): NotificationResponse? {
        if (postOwnerId == commenterId) {
            return null  // 자신의 포스트는 알림 스킵
        }

        val commenter = requireSenderInfo(commenterId, Errors.User.USER_INFO_NOT_FOUND)
        val imageUrl = getPostImageUrl(postId)

        return createAndSendNotification(
            recipientId = postOwnerId,
            senderId = commenterId,
            type = NotificationType.COMMENT,
            title = "새 댓글",
            message = "${commenter.username}님이 회원님의 포스트에 댓글을 남겼습니다",
            targetType = NotificationTargetType.POST,
            targetId = postId,
            senderUsername = commenter.username,
            senderAvatarUrl = commenter.avatarUrl,
            imageUrl = imageUrl,
            deepLink = "/posts/$postId#comment-$commentId"
        )
    }

    suspend fun sendReplyNotification(
        commentOwnerId: Int,
        replierId: Int,
        postId: Int,
        commentId: Int,
        parentCommentId: Int
    ): NotificationResponse? {
        if (commentOwnerId == replierId) {
            return null  // 자신의 댓글은 알림 스킵
        }

        val replier = requireSenderInfo(replierId, Errors.User.USER_INFO_NOT_FOUND)
        val imageUrl = getPostImageUrl(postId)

        return createAndSendNotification(
            recipientId = commentOwnerId,
            senderId = replierId,
            type = NotificationType.REPLY,
            title = "새 답글",
            message = "${replier.username}님이 회원님의 댓글에 답글을 남겼습니다",
            targetType = NotificationTargetType.COMMENT,
            targetId = commentId,
            senderUsername = replier.username,
            senderAvatarUrl = replier.avatarUrl,
            imageUrl = imageUrl,
            // #comment-부모, #reply-답글 앵커
            deepLink = "/posts/$postId#comment-$parentCommentId#reply-$commentId"
        )
    }

    suspend fun sendMentionNotification(
        mentionedUserId: Int,
        mentionerId: Int,
        postId: Int,
        commentId: Int,
        content: String
    ): NotificationResponse? {
        if (mentionedUserId == mentionerId) {
            return null  // 자기 자신 멘션은 알림 스킵
        }

        val mentioner = requireSenderInfo(mentionerId, Errors.User.USER_NOT_FOUND)

        val preview = if (content.length > 50) content.take(50) + "..." else content  // 50자 초과 시 말줄임
        val imageUrl = getPostImageUrl(postId)

        return createAndSendNotification(
            recipientId = mentionedUserId,
            senderId = mentionerId,
            type = NotificationType.MENTION,
            title = "${mentioner.username}님이 회원님을 언급했습니다",
            message = preview,
            targetType = NotificationTargetType.COMMENT,
            targetId = commentId,
            senderUsername = mentioner.username,
            senderAvatarUrl = mentioner.avatarUrl,
            imageUrl = imageUrl,
            deepLink = "/posts/$postId#comment-$commentId"
        )
    }

    // 주문 알림
    suspend fun sendOrderCreatedNotification(
        userId: Int,
        orderId: Int,
        orderNumber: String
    ): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = NotificationType.ORDER_CREATED,
            title = "주문 접수 완료",
            message = "주문이 접수되었습니다 (주문번호: $orderNumber)",
            targetType = NotificationTargetType.ORDER,
            targetId = orderId,
            deepLink = "/orders/$orderId"
        )
    }

    suspend fun sendOrderReceivedNotification(
        creatorId: Int,
        buyerId: Int,
        orderId: Int,
        orderNumber: String
    ): NotificationResponse {
        val buyer = requireSenderInfo(buyerId, Errors.Notification.BUYER_NOT_FOUND)

        return createAndSendNotification(
            recipientId = creatorId,
            senderId = buyerId,
            type = NotificationType.ORDER_RECEIVED,
            title = "새 주문 알림",
            message = "새로운 주문이 들어왔습니다 (주문번호: $orderNumber)",
            // 판매자 수신, targetType 없음
            targetType = null,
            targetId = orderId,
            senderUsername = buyer.username,
            senderAvatarUrl = buyer.avatarUrl,
            deepLink = "/creator/orders/$orderId"
        )
    }

    suspend fun sendOrderConfirmedNotification(
        userId: Int,
        creatorId: Int,
        orderId: Int
    ): NotificationResponse {
        val creator = findSenderInfo(creatorId)

        return createAndSendNotification(
            recipientId = userId,
            senderId = creatorId,
            type = NotificationType.ORDER_CONFIRMED,
            title = "주문 확정",
            message = "판매자가 주문을 확인했습니다",
            targetType = NotificationTargetType.ORDER,
            targetId = orderId,
            senderUsername = creator?.username,
            senderAvatarUrl = creator?.avatarUrl,
            deepLink = "/orders/$orderId"
        )
    }

    suspend fun sendOrderCancelledNotification(
        recipientId: Int,
        cancelledById: Int?,
        orderId: Int,
        recipientIsSeller: Boolean = false
    ): NotificationResponse {
        val cancelledBy = cancelledById?.let { findSenderInfo(it) }

        return createAndSendNotification(
            recipientId = recipientId,
            senderId = cancelledById,
            type = NotificationType.ORDER_CANCELLED,
            title = "주문 취소",
            message = "주문이 취소되었습니다",
            // 판매자면 판매관리, 구매자면 주문상세로
            targetType = if (recipientIsSeller) null else NotificationTargetType.ORDER,
            targetId = orderId,
            senderUsername = cancelledBy?.username,
            senderAvatarUrl = cancelledBy?.avatarUrl,
            deepLink = if (recipientIsSeller) "/creator/orders/$orderId" else "/orders/$orderId"
        )
    }

    suspend fun sendProductReviewedNotification(
        creatorId: Int,
        userId: Int,
        productId: Int,
        reviewId: Int
    ): NotificationResponse {
        val reviewer = requireSenderInfo(userId, Errors.Notification.REVIEWER_NOT_FOUND)
        val imageUrl = getProductImageUrl(productId)

        return createAndSendNotification(
            recipientId = creatorId,
            senderId = userId,
            type = NotificationType.PRODUCT_REVIEWED,
            title = "새 리뷰 알림",
            message = "${reviewer.username}님이 상품에 리뷰를 남겼습니다",
            // 리뷰 목록 딥링크, #review 앵커
            targetType = null,
            targetId = productId,
            senderUsername = reviewer.username,
            senderAvatarUrl = reviewer.avatarUrl,
            imageUrl = imageUrl,
            deepLink = "/products/$productId/reviews#review-$reviewId"
        )
    }

    // 배송 알림
    suspend fun sendShippingStartedNotification(
        userId: Int,
        orderId: Int,
        trackingNumber: String
    ): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = NotificationType.SHIPPING_STARTED,
            title = "배송이 시작되었습니다",
            message = "주문하신 상품의 배송이 시작되었습니다. 운송장번호: $trackingNumber",
            targetType = NotificationTargetType.ORDER,
            targetId = orderId,
            deepLink = "/orders/$orderId"
        )
    }

    suspend fun sendShippingInTransitNotification(
        userId: Int,
        orderId: Int
    ): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = NotificationType.SHIPPING_IN_TRANSIT,
            title = "상품이 배송중입니다",
            message = "주문하신 상품이 배송 중입니다.",
            targetType = NotificationTargetType.ORDER,
            targetId = orderId,
            deepLink = "/orders/$orderId"
        )
    }

    suspend fun sendOutForDeliveryNotification(
        userId: Int,
        orderId: Int
    ): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = NotificationType.SHIPPING_OUT_FOR_DELIVERY,
            title = "배송 기사님이 상품을 배송 중입니다",
            message = "곧 도착 예정입니다. 배송 받으실 준비를 해주세요!",
            targetType = NotificationTargetType.ORDER,
            targetId = orderId,
            deepLink = "/orders/$orderId"
        )
    }

    suspend fun sendShippingDeliveredNotification(
        userId: Int,
        orderId: Int
    ): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = NotificationType.SHIPPING_DELIVERED,
            title = "배송이 완료되었습니다",
            message = "주문하신 상품이 배송 완료되었습니다. 리뷰를 남겨주세요!",
            targetType = NotificationTargetType.ORDER,
            targetId = orderId,
            deepLink = "/orders/$orderId"
        )
    }

    // 채팅 알림
    suspend fun sendChatMessageNotification(
        recipientId: Int,
        senderId: Int,
        roomId: Int,
        messagePreview: String
    ): NotificationResponse? {
        if (recipientId == senderId) {
            return null  // 자기 자신에게 보낸 메시지는 알림 스킵
        }

        val sender = requireSenderInfo(senderId, Errors.Notification.SENDER_NOT_FOUND)

        return createAndSendNotification(
            recipientId = recipientId,
            senderId = senderId,
            type = NotificationType.CHAT_MESSAGE,
            title = "새 메시지",
            message = "${sender.username}님이 메시지를 보냈습니다: $messagePreview",
            targetType = NotificationTargetType.CHAT,
            targetId = roomId,
            senderUsername = sender.username,
            senderAvatarUrl = sender.avatarUrl,
            deepLink = "/chats/$roomId",
            messagePreview = messagePreview  // FCM 데이터에 별도 전달
        )
    }

    // 포인트·크리에이터 알림
    suspend fun sendPointEarnedNotification(
        userId: Int,
        amount: BigDecimal
    ): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = NotificationType.POINT_EARNED,
            title = "포인트 적립 완료",
            message = "${amount.toInt()}P가 적립되었습니다",
            // 포인트 화면으로 이동
            targetType = NotificationTargetType.POINT,
            targetId = null,
            deepLink = "/points/history"
        )
    }

    suspend fun sendCreatorApprovedNotification(
        userId: Int,
        applicationId: Int
    ): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = NotificationType.CREATOR_APPROVED,
            title = "크리에이터 승인 완료!",
            message = "크리에이터로 승인되었습니다. 이제 상품을 등록하고 판매할 수 있습니다!",
            targetType = NotificationTargetType.APPLICATION,
            targetId = applicationId,
            deepLink = "/creator/dashboard"
        )
    }

    suspend fun sendCreatorRejectedNotification(
        userId: Int,
        applicationId: Int,
        rejectionReason: String
    ): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = NotificationType.CREATOR_REJECTED,
            title = "크리에이터 신청 거절",
            message = "신청이 거절되었습니다. 사유: $rejectionReason",
            targetType = NotificationTargetType.APPLICATION,
            targetId = applicationId,
            deepLink = "/creator/application"
        )
    }

    suspend fun sendCreatorDemotedNotification(
        userId: Int,
        reason: String
    ): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = NotificationType.CREATOR_DEMOTED,
            title = "크리에이터 권한 해제",
            message = "크리에이터 권한이 해제되었습니다. 사유: $reason",
            targetType = null,
            targetId = null,
            deepLink = "/creator/application"
        )
    }

    suspend fun sendPointExpiredNotification(userId: Int, amount: BigDecimal): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = NotificationType.POINT_EXPIRED,
            title = "포인트 만료",
            message = "${amount.toInt()}P가 만료되었습니다",
            targetType = NotificationTargetType.POINT,
            targetId = null,
            deepLink = "/points/history"
        )
    }

    // 알림 조회
    suspend fun getMyNotifications(
        userId: Int,
        isRead: Boolean?,
        page: Int,
        limit: Int
    ): NotificationListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val notifications = notificationRepository.findUserNotifications(userId, isRead, validPage, validLimit)
            val totalCount = notificationRepository.countTotalNotifications(userId, isRead)
            val unreadCount = notificationRepository.countUnreadNotifications(userId)

            val senderIds = notifications.mapNotNull { it.senderId }.distinct()

            val senders = if (senderIds.isNotEmpty()) {
                userRepository.findUsersByIds(senderIds).associateBy { it.id.value }  // 일괄 조회
            } else {
                emptyMap()
            }

            val notificationResponses = notifications.map { notification ->
                val sender = notification.senderId?.let { senderId -> senders[senderId] }
                notification.toNotificationResponse(
                    senderUsername = sender?.username,
                    senderAvatarUrl = sender?.profile?.let { it.avatarThumbUrl ?: it.avatarUrl }
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            NotificationListResponse(
                notifications = createPagedResponse(notificationResponses, pagination),
                unreadCount = unreadCount.toInt()
            )
        }
    }

    suspend fun getUnreadCount(userId: Int): UnreadCountResponse {
        return query {
            val count = notificationRepository.countUnreadNotifications(userId)
            UnreadCountResponse(count.toInt())
        }
    }

    // 읽음 처리/삭제
    suspend fun markAsReadByTarget(userId: Int, type: NotificationType, targetId: Int) {
        query { notificationRepository.markAsReadByTarget(userId, type, targetId) }  // 대상 없으면 조용히 무시
    }

    suspend fun markChatRoomNotificationsAsRead(userId: Int, roomId: Int): Int {
        return query { notificationRepository.markChatNotificationsAsRead(userId, roomId) }
    }

    suspend fun markAsRead(notificationId: Int, userId: Int) {
        val success = query {
            notificationRepository.markAsRead(notificationId, userId)
        }

        if (!success) {
            throw NotFoundException(Errors.Notification.NOTIFICATION_NOT_FOUND_OR_NO_PERMISSION)  // 대상 없거나 권한 없으면
        }
    }

    suspend fun markAllAsRead(userId: Int) {
        val success = query {
            notificationRepository.markAllAsRead(userId)
        }

        if (!success) {
            throw NotFoundException(Errors.Notification.NO_UNREAD_NOTIFICATIONS)  // 안읽은 알림 없으면
        }
    }

    suspend fun deleteNotification(notificationId: Int, userId: Int) {
        val success = query {
            notificationRepository.deleteNotification(notificationId, userId)
        }

        if (!success) {
            throw NotFoundException(Errors.Notification.NOTIFICATION_NOT_FOUND_OR_NO_PERMISSION)  // 대상 없거나 권한 없으면
        }
    }

    // 시스템 알림
    suspend fun sendNotificationToUser(
        userId: Int,
        type: NotificationType,
        title: String,
        message: String,
        targetType: String? = null,
        targetId: Int? = null
    ): NotificationResponse {
        return createAndSendNotification(
            recipientId = userId,
            senderId = null,
            type = type,
            title = title,
            message = message,
            targetType = targetType,
            targetId = targetId,
            deepLink = targetType?.let { "/${it.lowercase()}s/$targetId" }  // targetType 기반 딥링크 자동 생성
        )
    }

    // 팔로워 일괄 알림
    suspend fun sendNewPostNotifications(
        creatorId: Int,
        creatorUsername: String,
        postId: Int,
        followerIds: List<Int>
    ) {
        if (followerIds.isEmpty()) return

        val title = "새 게시물"
        val message = "${creatorUsername}님이 새 게시물을 올렸습니다"
        val deepLink = "/posts/$postId"
        val senderAvatarUrl = getUserProfileImageUrl(creatorId)
        val imageUrl = getPostImageUrl(postId)
        sendBatchCreatorNotifications(
            recipientIds = followerIds,
            senderId = creatorId,
            senderUsername = creatorUsername,
            senderAvatarUrl = senderAvatarUrl,
            type = NotificationType.NEW_POST,
            title = title,
            message = message,
            targetType = NotificationTargetType.POST,
            targetId = postId,
            imageUrl = imageUrl,
            deepLink = deepLink
        )
    }

    suspend fun sendNewProductNotifications(
        creatorId: Int,
        creatorUsername: String,
        productId: Int,
        followerIds: List<Int>
    ) {
        if (followerIds.isEmpty()) return

        val title = "새 상품"
        val message = "${creatorUsername}님이 새 상품을 등록했습니다"
        val deepLink = "/products/$productId"
        val senderAvatarUrl = getUserProfileImageUrl(creatorId)
        val imageUrl = getProductImageUrl(productId)
        sendBatchCreatorNotifications(
            recipientIds = followerIds,
            senderId = creatorId,
            senderUsername = creatorUsername,
            senderAvatarUrl = senderAvatarUrl,
            type = NotificationType.NEW_PRODUCT,
            title = title,
            message = message,
            targetType = NotificationTargetType.PRODUCT,
            targetId = productId,
            imageUrl = imageUrl,
            deepLink = deepLink
        )
    }

    // 알림 설정
    suspend fun getPreferences(userId: Int): NotificationPreferenceDao? {
        return query {
            preferenceRepository.findByUserId(userId)
        }
    }

    suspend fun updatePreferences(
        userId: Int,
        socialEnabled: Boolean?,
        creatorActivityEnabled: Boolean?,
        commerceEnabled: Boolean?,
        chatEnabled: Boolean?,
        systemEnabled: Boolean?
    ): NotificationPreferenceDao? {
        return query {
            preferenceRepository.update(
                userId = userId,
                socialEnabled = socialEnabled,
                creatorActivityEnabled = creatorActivityEnabled,
                commerceEnabled = commerceEnabled,
                chatEnabled = chatEnabled,
                systemEnabled = systemEnabled
            )
        }
    }

    // 오래된 알림 정리
    suspend fun cleanupOldNotifications(retentionDays: Int = 90): Int {
        val now = nowUtc()
        val cutoffDate = now.date.minus(DatePeriod(days = retentionDays))
        val cutoff = cutoffDate.atTime(now.hour, now.minute, now.second, now.nanosecond)

        return query {
            notificationRepository.deactivateOlderThan(cutoff)
        }
    }

    // 일괄 발송·FCM 폴백
    private suspend fun sendBatchCreatorNotifications(
        recipientIds: List<Int>,
        senderId: Int,
        senderUsername: String,
        senderAvatarUrl: String?,
        type: NotificationType,
        title: String,
        message: String,
        targetType: String,
        targetId: Int,
        imageUrl: String?,
        deepLink: String
    ) {
        if (recipientIds.isEmpty()) return

        val metadataByRecipient = recipientIds.associateWith { recipientId ->
            buildDedupKey(
                recipientId = recipientId,
                senderId = senderId,
                type = type,
                title = title,
                message = message,
                targetType = targetType,
                targetId = targetId,
                deepLink = deepLink
            )
        }

        val existingMetadata = query {
            notificationRepository.findActiveByMetadataList(metadataByRecipient.values.toList())
                .mapNotNull { it.metadata }
                .toSet()
        }

        val recipientsToCreate = recipientIds.filter { recipientId ->
            metadataByRecipient[recipientId] !in existingMetadata
        }
        if (recipientsToCreate.isEmpty()) return

        val createdNotificationIds = query {
            val notifications = recipientsToCreate.map { recipientId ->
                NotificationData(
                    recipientId = recipientId,
                    senderId = senderId,
                    type = type,
                    title = title,
                    message = message,
                    targetType = targetType,
                    targetId = targetId,
                    imageUrl = imageUrl,
                    deepLink = deepLink,
                    metadata = metadataByRecipient[recipientId]
                )
            }
            notificationRepository.batchCreateNotifications(notifications)
        }
        val idsByRecipient = recipientsToCreate.zip(createdNotificationIds).toMap()

        // 알림 설정 판정과 응답 일괄 생성
        val pushAllowedByRecipient = query {
            preferenceRepository.findByUserIds(recipientsToCreate)
                .associate { it.userId to allowsPush(it, type) }
        }
        val responseById = query {
            notificationRepository.findByIds(createdNotificationIds)
                .associate { it.id.value to it.toNotificationResponse(senderUsername, senderAvatarUrl) }
        }

        val usersNeedingFcm = mutableSetOf<Int>()

        recipientsToCreate.forEach { recipientId ->
            val notificationId = idsByRecipient[recipientId] ?: return@forEach

            // 설정 없으면 기본 전송
            if (pushAllowedByRecipient[recipientId] == false) return@forEach

            val response = responseById[notificationId] ?: return@forEach

            val deliveredByWebSocket = webSocketManager.sendToUser(
                recipientId,
                Json.encodeToString(
                    RealtimeFrame(
                        type = RealtimeFrameType.NOTIFICATION,
                        data = response
                    )
                )
            )

            if (!deliveredByWebSocket) {
                usersNeedingFcm += recipientId
            }
        }

        if (usersNeedingFcm.isNotEmpty()) {
            sendBatchFcmFallback(
                recipientIds = usersNeedingFcm.toList(),
                type = type,
                title = title,
                message = message,
                targetType = targetType,
                targetId = targetId,
                senderAvatarUrl = senderAvatarUrl,
                imageUrl = imageUrl,
                deepLink = deepLink
            )
        }
    }

    private suspend fun sendFcmFallback(
        recipientId: Int,
        notificationId: Int,
        type: NotificationType,
        title: String,
        message: String,
        targetType: String?,
        targetId: Int?,
        senderAvatarUrl: String?,
        imageUrl: String?,
        deepLink: String?,
        senderUsername: String?,
        messagePreview: String? = null
    ) {
        val fids = query {
            userRepository.getDeviceFidsForUser(recipientId)
        }
        if (fids.isEmpty()) return

        val result = fcmService.sendBatchPush(
            fids = fids,
            title = title,
            body = message,
            data = mapOf(
                "notificationId" to notificationId.toString(),
                "title" to title,
                "message" to message,
                "type" to type.name,
                "targetType" to (targetType ?: ""),
                "targetId" to (targetId?.toString() ?: ""),
                "senderAvatarUrl" to (senderAvatarUrl ?: ""),
                "imageUrl" to (imageUrl ?: ""),
                "deepLink" to (deepLink ?: ""),
                "senderUsername" to (senderUsername ?: ""),
                "messagePreview" to (messagePreview ?: "")
            )
        )

        cleanupInvalidFids(result.invalidFids)
    }

    private suspend fun sendBatchFcmFallback(
        recipientIds: List<Int>,
        type: NotificationType,
        title: String,
        message: String,
        targetType: String,
        targetId: Int,
        senderAvatarUrl: String?,
        imageUrl: String?,
        deepLink: String
    ) {
        val fids = query {
            userRepository.getDeviceFidsForUsers(recipientIds)
        }

        if (fids.isEmpty()) return

        val result = fcmService.sendBatchPush(
            fids = fids,
            title = title,
            body = message,
            data = mapOf(
                "title" to title,
                "message" to message,
                "type" to type.name,
                "targetType" to targetType,
                "targetId" to targetId.toString(),
                "senderAvatarUrl" to (senderAvatarUrl ?: ""),
                "imageUrl" to (imageUrl ?: ""),
                "deepLink" to deepLink
            )
        )

        cleanupInvalidFids(result.invalidFids)
    }

    private suspend fun cleanupInvalidFids(invalidFids: List<String>) {
        if (invalidFids.isEmpty()) return

        query {
            userRepository.deleteDeviceTokensByFids(invalidFids.distinct())
        }
    }

    // 공통 보조
    private fun needsDedup(type: NotificationType): Boolean {
        return type != NotificationType.CHAT_MESSAGE
    }

    private suspend fun getPostImageUrl(postId: Int): String? {
        return query {
            val post = postRepository.findPostById(postId) ?: return@query null
            // 잠금 글 이미지는 알림에서 제외
            if (post.requiredTier != SubscriptionPlanTier.FREE || post.isSecret) return@query null
            // 썸네일 우선, 없으면 원본 url
            PostMediaDao.find { PostMediaTable.postId eq postId }
                .orderBy(PostMediaTable.sortOrder to SortOrder.ASC)
                .firstOrNull()
                ?.let { it.thumbnailUrl ?: it.url }
        }
    }

    private suspend fun getProductImageUrl(productId: Int): String? {
        return query {
            productRepository.findProductById(productId)?.imageUrls.decodeJsonToList().firstOrNull()
        }
    }

    private suspend fun getUserProfileImageUrl(userId: Int): String? {
        return query {
            userRepository.findUserById(userId)?.profile?.let { it.avatarThumbUrl ?: it.avatarUrl }
        }
    }

    private fun buildDedupKey(
        recipientId: Int,
        senderId: Int?,
        type: NotificationType,
        title: String,
        message: String,
        targetType: String?,
        targetId: Int?,
        deepLink: String?
    ): String {
        val timeBucket = if (targetId == null) {
            val now = nowUtc()
            "|${now.date}|${now.hour}|${now.minute}"
        } else {
            ""
        }

        return buildString {
            append("v1")
            append("|").append(recipientId)
            append("|").append(senderId ?: 0)
            append("|").append(type.name)
            append("|").append(targetType ?: "-")
            append("|").append(targetId ?: -1)
            append("|").append(deepLink ?: "-")
            append("|").append(title)
            append("|").append(message)
            append(timeBucket)
        }
    }
}
