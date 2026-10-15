package com.ninezero.features.notification.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.NotificationCategory
import com.ninezero.core.common.config.NotificationTargetType
import com.ninezero.core.common.config.NotificationType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.getCategory
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.CursorResponse
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toCursorPage
import com.ninezero.core.common.util.validateCursorLimit
import com.ninezero.core.database.entities.notification.NotificationPreferenceDao
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.fcm.FcmService
import com.ninezero.core.websocket.RealtimeFrame
import com.ninezero.core.websocket.RealtimeFrameType
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.notification.data.NotificationData
import com.ninezero.features.notification.data.NotificationPreferenceRepository
import com.ninezero.features.notification.data.NotificationRepository
import com.ninezero.features.notification.defaultPreferenceResponse
import com.ninezero.features.notification.presentation.models.response.NotificationListResponse
import com.ninezero.features.notification.presentation.models.response.NotificationPreferenceResponse
import com.ninezero.features.notification.presentation.models.response.NotificationResponse
import com.ninezero.features.notification.presentation.models.response.UnreadCountResponse
import com.ninezero.features.notification.toNotificationPreferenceResponse
import com.ninezero.features.notification.toNotificationResponse
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.user.data.UserRepository
import io.ktor.serialization.kotlinx.json.DefaultJson
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SortOrder
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    private val preferenceRepository: NotificationPreferenceRepository,
    private val postRepository: PostRepository,
    private val productRepository: ProductRepository,
    private val webSocketManager: WebSocketManager,
    private val fcmService: FcmService
) {
    private data class SenderInfo(
        val username: String,
        val avatarUrl: String?
    )

    /** 알림 한 건 내용 */
    private data class NotificationDraft(
        val recipientId: Int,
        val senderId: Int?,
        val type: NotificationType,
        val title: String,
        val message: String,
        val targetType: String?,
        val targetId: Int?,
        val senderUsername: String? = null,
        val senderAvatarUrl: String? = null,
        val imageUrl: String? = null,
        val deepLink: String? = null
    )

    // 알림 생성 공통
    private suspend fun requireSenderInfo(userId: Int): SenderInfo {
        val user = userRepository.findUserById(userId)
            ?: throw NotFoundException(Errors.User.USER_INFO_NOT_FOUND)

        return SenderInfo(
            username = user.username,
            avatarUrl = user.profile?.let { it.avatarThumbUrl ?: it.avatarUrl }
        )
    }

    private suspend fun findSenderInfo(userId: Int): SenderInfo? {
        return userRepository.findUserById(userId)?.let { user ->
            SenderInfo(
                username = user.username,
                avatarUrl = user.profile?.let { it.avatarThumbUrl ?: it.avatarUrl }
            )
        }
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

    /** 전송만 커밋 뒤에 하는 알림 생성 트랜잭션 */
    private suspend fun createAndSendNotification(buildDraft: suspend () -> NotificationDraft): NotificationResponse {
        val (draft, response, pushFids) = query {
            val draft = buildDraft()
            val metadata = buildDedupKey(
                recipientId = draft.recipientId,
                senderId = draft.senderId,
                type = draft.type,
                title = draft.title,
                message = draft.message,
                targetType = draft.targetType,
                targetId = draft.targetId,
                deepLink = draft.deepLink
            )

            val existingNotification = notificationRepository.findActiveByMetadata(metadata)
            val notification = existingNotification ?: notificationRepository.createNotification(
                recipientId = draft.recipientId,
                senderId = draft.senderId,
                type = draft.type,
                title = draft.title,
                message = draft.message,
                targetType = draft.targetType,
                targetId = draft.targetId,
                imageUrl = draft.imageUrl,
                deepLink = draft.deepLink,
                metadata = metadata
            )
            // 새 알림만 전송, 소켓 실패 때 쓸 기기 토큰까지 미리
            val pushFids = if (existingNotification == null && allowsPush(preferenceRepository.findByUserId(draft.recipientId), draft.type)) {
                userRepository.getDeviceFidsForUser(draft.recipientId)
            } else {
                null
            }

            Triple(draft, notification.toNotificationResponse(draft.senderUsername, draft.senderAvatarUrl), pushFids)
        }

        if (pushFids != null) {
            val deliveredByWebSocket = webSocketManager.sendToUser(
                draft.recipientId,
                DefaultJson.encodeToString(
                    RealtimeFrame(
                        type = RealtimeFrameType.NOTIFICATION,
                        data = response
                    )
                )
            )

            if (!deliveredByWebSocket) {
                sendFcmFallback(
                    fids = pushFids,
                    notificationId = response.id,
                    type = draft.type,
                    title = draft.title,
                    message = draft.message,
                    targetType = draft.targetType,
                    targetId = draft.targetId,
                    senderAvatarUrl = draft.senderAvatarUrl,
                    imageUrl = draft.imageUrl,
                    deepLink = draft.deepLink,
                    senderUsername = draft.senderUsername
                )
            }
        }

        return response
    }

    // 소셜 알림
    suspend fun sendFollowNotification(followerId: Int, followedId: Int): NotificationResponse {
        return createAndSendNotification {
            val follower = requireSenderInfo(followerId)

            NotificationDraft(
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
    }

    suspend fun sendLikePostNotification(
        postOwnerId: Int,
        likerId: Int,
        postId: Int
    ): NotificationResponse? {
        if (postOwnerId == likerId) {
            return null  // 자신의 포스트는 알림 스킵
        }

        return createAndSendNotification {
            val liker = requireSenderInfo(likerId)
            val imageUrl = getPostImageUrl(postId)

            NotificationDraft(
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
    }

    suspend fun sendLikeCommentNotification(
        commentOwnerId: Int,
        likerId: Int,
        commentId: Int,
        postId: Int
    ): NotificationResponse? {
        if (commentOwnerId == likerId) {
            return null  // 자신의 댓글은 알림 스킵
        }

        return createAndSendNotification {
            val liker = requireSenderInfo(likerId)
            val imageUrl = getPostImageUrl(postId)

            NotificationDraft(
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

        return createAndSendNotification {
            val commenter = requireSenderInfo(commenterId)
            val imageUrl = getPostImageUrl(postId)

            NotificationDraft(
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

        return createAndSendNotification {
            val replier = requireSenderInfo(replierId)
            val imageUrl = getPostImageUrl(postId)

            NotificationDraft(
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
    }

    suspend fun sendMentionNotification(
        mentionedUserId: Int,
        mentionerId: Int,
        postId: Int,
        commentId: Int,
        content: String,
        canViewContent: Boolean
    ): NotificationResponse? {
        if (mentionedUserId == mentionerId) {
            return null  // 자기 자신 멘션은 알림 스킵
        }

        return createAndSendNotification {
            val mentioner = requireSenderInfo(mentionerId)

            val preview = when {
                !canViewContent -> "댓글에서 회원님을 언급했습니다"  // 잠긴 글 본문은 알림에 싣지 않음
                content.length > Constants.Social.MENTION_NOTIFICATION_PREVIEW_LENGTH ->
                    content.take(Constants.Social.MENTION_NOTIFICATION_PREVIEW_LENGTH) + "..."  // 초과 시 말줄임
                else -> content
            }
            val imageUrl = getPostImageUrl(postId)

            NotificationDraft(
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
    }

    // 주문 알림
    suspend fun sendOrderCreatedNotification(
        userId: Int,
        orderId: Int,
        orderNumber: String
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
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
    }

    suspend fun sendOrderReceivedNotification(
        creatorId: Int,
        buyerId: Int,
        orderId: Int,
        orderNumber: String
    ): NotificationResponse {
        return createAndSendNotification {
            val buyer = requireSenderInfo(buyerId)

            NotificationDraft(
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
    }

    suspend fun sendOrderConfirmedNotification(
        userId: Int,
        creatorId: Int,
        orderId: Int
    ): NotificationResponse {
        return createAndSendNotification {
            val creator = findSenderInfo(creatorId)

            NotificationDraft(
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
    }

    suspend fun sendOrderCancelledNotification(
        recipientId: Int,
        cancelledById: Int?,
        orderId: Int,
        recipientIsSeller: Boolean = false
    ): NotificationResponse {
        return createAndSendNotification {
            val cancelledBy = cancelledById?.let { findSenderInfo(it) }

            NotificationDraft(
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
    }

    suspend fun sendProductReviewedNotification(
        creatorId: Int,
        userId: Int,
        productId: Int,
        reviewId: Int
    ): NotificationResponse {
        return createAndSendNotification {
            val reviewer = requireSenderInfo(userId)
            val imageUrl = getProductImageUrl(productId)

            NotificationDraft(
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
    }

    // 배송 알림
    suspend fun sendShippingStartedNotification(
        userId: Int,
        orderId: Int,
        trackingNumber: String
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
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
    }

    suspend fun sendTrackingChangedNotification(
        userId: Int,
        orderId: Int,
        carrier: String,
        trackingNumber: String
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
                recipientId = userId,
                senderId = null,
                type = NotificationType.SHIPPING_STARTED,
                title = "송장번호가 변경되었습니다",
                message = "주문하신 상품의 송장번호가 변경되었습니다. $carrier $trackingNumber",
                targetType = NotificationTargetType.ORDER,
                targetId = orderId,
                deepLink = "/orders/$orderId"
            )
        }
    }

    suspend fun sendShippingInTransitNotification(
        userId: Int,
        orderId: Int,
        creatorId: Int
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
                recipientId = userId,
                senderId = null,
                type = NotificationType.SHIPPING_IN_TRANSIT,
                title = "상품이 배송중입니다",
                message = "${sellerName(creatorId)}님이 보낸 상품이 배송 중입니다.",
                targetType = NotificationTargetType.ORDER,
                targetId = orderId,
                deepLink = "/orders/$orderId"
            )
        }
    }

    suspend fun sendOutForDeliveryNotification(
        userId: Int,
        orderId: Int,
        creatorId: Int
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
                recipientId = userId,
                senderId = null,
                type = NotificationType.SHIPPING_OUT_FOR_DELIVERY,
                title = "배송 기사님이 상품을 배송 중입니다",
                message = "${sellerName(creatorId)}님이 보낸 상품이 곧 도착합니다. 배송 받으실 준비를 해주세요!",
                targetType = NotificationTargetType.ORDER,
                targetId = orderId,
                deepLink = "/orders/$orderId"
            )
        }
    }

    suspend fun sendShippingDeliveredNotification(
        userId: Int,
        orderId: Int,
        creatorId: Int
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
                recipientId = userId,
                senderId = null,
                type = NotificationType.SHIPPING_DELIVERED,
                title = "배송이 완료되었습니다",
                message = "${sellerName(creatorId)}님이 보낸 상품이 배송 완료되었습니다. 리뷰를 남겨주세요!",
                targetType = NotificationTargetType.ORDER,
                targetId = orderId,
                deepLink = "/orders/$orderId"
            )
        }
    }

    /** 배송그룹 구분용 판매자명 */
    private suspend fun sellerName(creatorId: Int): String = findSenderInfo(creatorId)?.username ?: "판매자"

    // 채팅 알림
    /** 채팅은 알림 목록에 쌓지 않고 접속 안 한 수신자에게 푸시만 */
    suspend fun sendChatMessageNotification(
        recipientId: Int,
        senderId: Int,
        roomId: Int,
        messagePreview: String
    ) {
        if (recipientId == senderId) return  // 자기 자신에게 보낸 메시지는 알림 스킵
        if (webSocketManager.isUserConnected(recipientId)) return  // 접속 중이면 MESSAGE 프레임으로 받음

        // 설정·발신자·기기 토큰은 트랜잭션 1개
        val (sender, fids) = query {
            if (!allowsPush(preferenceRepository.findByUserId(recipientId), NotificationType.CHAT_MESSAGE)) {
                return@query null
            }
            requireSenderInfo(senderId) to userRepository.getDeviceFidsForUser(recipientId)
        } ?: return

        sendFcmFallback(
            fids = fids,
            notificationId = null,
            type = NotificationType.CHAT_MESSAGE,
            title = "새 메시지",
            message = "${sender.username}님이 메시지를 보냈습니다: $messagePreview",
            targetType = NotificationTargetType.CHAT,
            targetId = roomId,
            senderAvatarUrl = sender.avatarUrl,
            imageUrl = null,
            deepLink = "/chats/$roomId",
            senderUsername = sender.username,  // 채팅 그룹 요약 제목
            messagePreview = messagePreview
        )
    }

    // 포인트·크리에이터 알림
    suspend fun sendPointEarnedNotification(
        userId: Int,
        orderId: Int,
        amount: BigDecimal
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
                recipientId = userId,
                senderId = null,
                type = NotificationType.POINT_EARNED,
                title = "포인트 적립 완료",
                message = "${amount.toInt()}P가 적립되었습니다",
                // 포인트 화면으로 이동
                targetType = NotificationTargetType.POINT,
                targetId = orderId,  // 주문당 한 번 적립이라 적립 건 구분
                deepLink = "/points/history"
            )
        }
    }

    suspend fun sendPointExpiredNotification(userId: Int, amount: BigDecimal): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
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
    }

    suspend fun sendCreatorApprovedNotification(
        userId: Int,
        applicationId: Int
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
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
    }

    suspend fun sendCreatorRejectedNotification(
        userId: Int,
        applicationId: Int,
        rejectionReason: String
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
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
    }

    suspend fun sendCreatorDemotedNotification(
        userId: Int,
        reason: String
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
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
    }

    // 구독
    suspend fun sendNewSubscriberNotification(
        creatorId: Int,
        subscriberId: Int,
        subscriberUsername: String,
        planName: String
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
                recipientId = creatorId,
                senderId = null,
                type = NotificationType.SYSTEM_ANNOUNCEMENT,
                title = "새로운 구독자",
                message = "${subscriberUsername}님이 $planName 플랜을 구독했습니다.",
                // 중복 제거에 안 막히게 대상 없이 프로필로
                targetType = null,
                targetId = null,
                deepLink = "/users/$subscriberId"
            )
        }
    }

    suspend fun sendSubscriptionCancelScheduledNotification(
        creatorId: Int,
        subscriberId: Int,
        subscriberUsername: String,
        planName: String,
        expiresOn: LocalDate
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
                recipientId = creatorId,
                senderId = null,
                type = NotificationType.SYSTEM_ANNOUNCEMENT,
                title = "구독 취소 예정",
                message = "${subscriberUsername}님이 $planName 플랜 자동 갱신을 취소했습니다. (${expiresOn}까지 유지)",
                targetType = null,
                targetId = null,
                deepLink = "/users/$subscriberId"
            )
        }
    }

    suspend fun sendSubscriptionExpiredNotification(
        userId: Int,
        subscriptionId: Int,
        creatorUsername: String,
        planName: String
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
                recipientId = userId,
                senderId = null,
                type = NotificationType.SYSTEM_ANNOUNCEMENT,
                title = "구독 만료",
                message = "${creatorUsername}님의 $planName 플랜 구독이 만료되었습니다.",
                targetType = NotificationTargetType.SUBSCRIPTION,
                targetId = subscriptionId,
                deepLink = "/subscriptions/$subscriptionId"
            )
        }
    }

    /** 다음 결제일이 문구에 들어가 달마다 중복 제거 키가 달라짐 */
    suspend fun sendSubscriptionRenewedNotification(
        userId: Int,
        subscriptionId: Int,
        creatorUsername: String,
        planName: String,
        nextBillingDate: LocalDate
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
                recipientId = userId,
                senderId = null,
                type = NotificationType.SYSTEM_ANNOUNCEMENT,
                title = "구독 자동 갱신",
                message = "${creatorUsername}님의 $planName 플랜이 자동 갱신되었습니다. 다음 결제일은 ${nextBillingDate}입니다.",
                targetType = NotificationTargetType.SUBSCRIPTION,
                targetId = subscriptionId,
                deepLink = "/subscriptions/$subscriptionId"
            )
        }
    }

    suspend fun sendSubscriptionRenewalFailedNotification(
        userId: Int,
        subscriptionId: Int,
        creatorUsername: String,
        planName: String,
        expiresOn: LocalDate
    ): NotificationResponse {
        return createAndSendNotification {
            NotificationDraft(
                recipientId = userId,
                senderId = null,
                type = NotificationType.SYSTEM_ANNOUNCEMENT,
                title = "구독 자동 갱신 실패",
                message = "${creatorUsername}님의 $planName 플랜 자동 갱신에 실패했습니다. ${expiresOn}에 구독이 만료됩니다. 결제 정보를 확인해주세요.",
                targetType = NotificationTargetType.SUBSCRIPTION,
                targetId = subscriptionId,
                deepLink = "/subscriptions/$subscriptionId"
            )
        }
    }

    // 팔로워 일괄 알림
    suspend fun sendNewPostNotifications(
        creatorId: Int,
        creatorUsername: String,
        postId: Int,
        followerIds: List<Int>
    ) {
        if (followerIds.isEmpty()) return

        val (senderAvatarUrl, imageUrl) = query {
            getUserProfileImageUrl(creatorId) to getPostImageUrl(postId)
        }
        sendBatchCreatorNotifications(
            recipientIds = followerIds,
            senderId = creatorId,
            senderUsername = creatorUsername,
            senderAvatarUrl = senderAvatarUrl,
            type = NotificationType.NEW_POST,
            title = "새 포스트",
            message = "${creatorUsername}님이 새 포스트를 올렸습니다",
            targetType = NotificationTargetType.POST,
            targetId = postId,
            imageUrl = imageUrl,
            deepLink = "/posts/$postId"
        )
    }

    suspend fun sendNewProductNotifications(
        creatorId: Int,
        creatorUsername: String,
        productId: Int,
        followerIds: List<Int>
    ) {
        if (followerIds.isEmpty()) return

        val (senderAvatarUrl, imageUrl) = query {
            getUserProfileImageUrl(creatorId) to getProductImageUrl(productId)
        }
        sendBatchCreatorNotifications(
            recipientIds = followerIds,
            senderId = creatorId,
            senderUsername = creatorUsername,
            senderAvatarUrl = senderAvatarUrl,
            type = NotificationType.NEW_PRODUCT,
            title = "새 상품",
            message = "${creatorUsername}님이 새 상품을 등록했습니다",
            targetType = NotificationTargetType.PRODUCT,
            targetId = productId,
            imageUrl = imageUrl,
            deepLink = "/products/$productId"
        )
    }

    // 알림 조회
    suspend fun getMyNotifications(
        userId: Int,
        isRead: Boolean?,
        cursor: String?,
        limit: Int
    ): NotificationListResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            val (notifications, nextCursor) = notificationRepository.findUserNotifications(userId, isRead, pageCursor, validLimit + 1)
                .toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }
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

            NotificationListResponse(
                notifications = CursorResponse(notificationResponses, nextCursor, hasNext = nextCursor != null),
                unreadCount = unreadCount
            )
        }
    }

    suspend fun getUnreadCount(userId: Int): UnreadCountResponse {
        return query {
            val count = notificationRepository.countUnreadNotifications(userId)
            UnreadCountResponse(count)
        }
    }

    // 읽음 처리/삭제
    suspend fun markAsReadByTarget(userId: Int, type: NotificationType, targetId: Int) {
        query { notificationRepository.markAsReadByTarget(userId, type, targetId) }  // 대상 없으면 조용히 무시
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
        query {
            notificationRepository.markAllAsRead(userId)  // 안읽은 알림 없어도 성공
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

    /** 삭제된 글로 가는 새 글 알림 정리 */
    suspend fun deactivateNewPostNotifications(postId: Int): Int = query {
        notificationRepository.deactivateByTarget(NotificationType.NEW_POST, NotificationTargetType.POST, postId)
    }

    // 알림 설정
    suspend fun getPreferences(userId: Int): NotificationPreferenceResponse {
        return query {
            preferenceRepository.findByUserId(userId)?.toNotificationPreferenceResponse() ?: defaultPreferenceResponse()
        }
    }

    suspend fun updatePreferences(
        userId: Int,
        socialEnabled: Boolean?,
        creatorActivityEnabled: Boolean?,
        commerceEnabled: Boolean?,
        chatEnabled: Boolean?,
        systemEnabled: Boolean?
    ): NotificationPreferenceResponse {
        return query {
            preferenceRepository.update(
                userId = userId,
                socialEnabled = socialEnabled,
                creatorActivityEnabled = creatorActivityEnabled,
                commerceEnabled = commerceEnabled,
                chatEnabled = chatEnabled,
                systemEnabled = systemEnabled
            ).toNotificationPreferenceResponse()
        }
    }

    // 오래된 알림 정리
    suspend fun cleanupOldNotifications(retentionDays: Int): Int {
        val cutoff = (Clock.System.now() - retentionDays.days).toLocalDateTime(TimeZone.UTC)

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

        val responseByRecipient = query {
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
            recipientsToCreate.zip(notificationRepository.batchCreateNotifications(notifications))
                .associate { (recipientId, notification) ->
                    recipientId to notification.toNotificationResponse(senderUsername, senderAvatarUrl)
                }
        }

        // 알림 설정 판정
        val pushAllowedByRecipient = query {
            preferenceRepository.findByUserIds(recipientsToCreate)
                .associate { it.userId to allowsPush(it, type) }
        }

        val usersNeedingFcm = mutableSetOf<Int>()

        recipientsToCreate.forEach { recipientId ->
            val response = responseByRecipient[recipientId] ?: return@forEach

            // 설정 없으면 기본 전송
            if (pushAllowedByRecipient[recipientId] == false) return@forEach

            val deliveredByWebSocket = webSocketManager.sendToUser(
                recipientId,
                DefaultJson.encodeToString(
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
        fids: List<String>,
        notificationId: Int?,
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
        if (fids.isEmpty()) return

        val invalidFids = fcmService.sendBatchPush(
            fids = fids,
            title = title,
            body = message,
            data = mapOf(
                "notificationId" to (notificationId?.toString() ?: ""),  // 채팅은 알림 행 없음
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

        cleanupInvalidFids(invalidFids)
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

        val invalidFids = fcmService.sendBatchPush(
            fids = fids,
            title = title,
            body = message,
            data = mapOf(
                "type" to type.name,
                "targetType" to targetType,
                "targetId" to targetId.toString(),
                "senderAvatarUrl" to (senderAvatarUrl ?: ""),
                "imageUrl" to (imageUrl ?: ""),
                "deepLink" to deepLink
            )
        )

        cleanupInvalidFids(invalidFids)
    }

    private suspend fun cleanupInvalidFids(invalidFids: List<String>) {
        if (invalidFids.isEmpty()) return

        query {
            userRepository.deleteDeviceTokensByFids(invalidFids.distinct())
        }
    }

    // 공통 보조
    private suspend fun getPostImageUrl(postId: Int): String? {
        val post = postRepository.findPostById(postId) ?: return null
        // 잠금 글 이미지는 알림에서 제외
        if (post.requiredTier != SubscriptionPlanTier.FREE || post.isSecret) return null
        // 썸네일 우선, 없으면 원본 url
        return PostMediaDao.find { PostMediaTable.postId eq postId }
            .orderBy(PostMediaTable.sortOrder to SortOrder.ASC)
            .firstOrNull()
            ?.let { it.thumbnailUrl ?: it.url }
    }

    private suspend fun getProductImageUrl(productId: Int): String? {
        return productRepository.findProductById(productId)?.imageUrls.decodeJsonToList().firstOrNull()
    }

    private suspend fun getUserProfileImageUrl(userId: Int): String? {
        return userRepository.findUserById(userId)?.profile?.let { it.avatarThumbUrl ?: it.avatarUrl }
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
