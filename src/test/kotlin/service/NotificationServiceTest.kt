package com.ninezero.service

import com.ninezero.core.common.config.NotificationType
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.fcm.FcmService
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.notification.data.NotificationPreferenceRepositoryImpl
import com.ninezero.features.notification.data.NotificationRepositoryImpl
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * NotificationService 테스트
 *
 * 테스트 케이스: 15개
 * - 알림 전송: 7개
 * - 알림 조회: 3개
 * - 알림 관리: 5개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationServiceTest {

    private lateinit var notificationService: NotificationService
    private lateinit var notificationRepository: NotificationRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var fcmService: FcmService
    private lateinit var preferenceRepository: NotificationPreferenceRepositoryImpl
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var productRepository: ProductRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            notificationRepository = NotificationRepositoryImpl()
            userRepository = UserRepositoryImpl()
            preferenceRepository = NotificationPreferenceRepositoryImpl()
            postRepository = PostRepositoryImpl()
            productRepository = ProductRepositoryImpl()

            // Mock Services
            webSocketManager = mockk(relaxed = true)
            fcmService = mockk(relaxed = true)

            coEvery { webSocketManager.isUserConnected(any()) } returns false

            notificationService = NotificationService(
                notificationRepository = notificationRepository,
                userRepository = userRepository,
                webSocketManager = webSocketManager,
                fcmService = fcmService,
                preferenceRepository = preferenceRepository,
                postRepository = postRepository,
                productRepository = productRepository
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    // ===== 알림 전송 테스트 (7개) =====

    @Test
    fun `팔로우 알림 전송 성공`() {
        runBlocking {
            val follower = TestFixtures.createTestUser(email = "follower@example.com", username = "follower")
            val followed = TestFixtures.createTestUser(email = "followed@example.com", username = "followed")

            val notification = notificationService.sendFollowNotification(follower.id.value, followed.id.value)

            assertNotNull(notification)
            assertEquals(NotificationType.FOLLOW, notification.type)
            assertEquals(followed.id.value, notification.recipientId)
            assertEquals(follower.id.value, notification.senderId)
        }
    }

    @Test
    fun `포스트 좋아요 알림 전송 성공`() {
        runBlocking {
            val postOwner = TestFixtures.createTestUser(email = "owner@example.com", username = "owner")
            val liker = TestFixtures.createTestUser(email = "liker@example.com", username = "liker")

            val notification = notificationService.sendLikePostNotification(
                postOwnerId = postOwner.id.value,
                likerId = liker.id.value,
                postId = 1
            )

            assertNotNull(notification)
            assertEquals(NotificationType.LIKE_POST, notification.type)
            assertEquals(postOwner.id.value, notification.recipientId)
        }
    }

    @Test
    fun `포스트 좋아요 알림 - 자신의 포스트는 스킵`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val notification = notificationService.sendLikePostNotification(
                postOwnerId = user.id.value,
                likerId = user.id.value,
                postId = 1
            )

            assertNull(notification)
        }
    }

    @Test
    fun `댓글 알림 전송 성공`() {
        runBlocking {
            val postOwner = TestFixtures.createTestUser(email = "owner@example.com", username = "owner")
            val commenter = TestFixtures.createTestUser(email = "commenter@example.com", username = "commenter")

            val notification = notificationService.sendCommentNotification(
                postOwnerId = postOwner.id.value,
                commenterId = commenter.id.value,
                postId = 1,
                commentId = 1
            )

            assertNotNull(notification)
            assertEquals(NotificationType.COMMENT, notification.type)
            assertEquals(postOwner.id.value, notification.recipientId)
        }
    }

    @Test
    fun `주문 생성 알림 전송 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val notification = notificationService.sendOrderCreatedNotification(
                userId = user.id.value,
                orderId = 1,
                orderNumber = "ORDER-001"
            )

            assertNotNull(notification)
            assertEquals(NotificationType.ORDER_CREATED, notification.type)
            assertEquals(user.id.value, notification.recipientId)
            assertNull(notification.senderId)
        }
    }

    @Test
    fun `배송 시작 알림 전송 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val notification = notificationService.sendShippingStartedNotification(
                userId = user.id.value,
                orderId = 1,
                trackingNumber = "1234567890"
            )

            assertNotNull(notification)
            assertEquals(NotificationType.SHIPPING_STARTED, notification.type)
            assertEquals(user.id.value, notification.recipientId)
        }
    }

    @Test
    fun `크리에이터 승인 알림 전송 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val notification = notificationService.sendCreatorApprovedNotification(
                userId = user.id.value,
                applicationId = 1
            )

            assertNotNull(notification)
            assertEquals(NotificationType.CREATOR_APPROVED, notification.type)
            assertEquals(user.id.value, notification.recipientId)
        }
    }

    // ===== 알림 조회 테스트 (3개) =====

    @Test
    fun `내 알림 목록 조회 성공 - 빈 목록`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val response = notificationService.getMyNotifications(
                userId = user.id.value,
                isRead = null,
                page = 1,
                limit = 10
            )

            assertNotNull(response)
            assertEquals(0, response.notifications.items.size)
            assertEquals(0, response.unreadCount)
        }
    }

    @Test
    fun `내 알림 목록 조회 성공 - 여러 알림`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val follower1 = TestFixtures.createTestUser(email = "f1@example.com", username = "f1")
            val follower2 = TestFixtures.createTestUser(email = "f2@example.com", username = "f2")

            // 알림 생성
            notificationService.sendFollowNotification(follower1.id.value, user.id.value)
            notificationService.sendFollowNotification(follower2.id.value, user.id.value)

            val response = notificationService.getMyNotifications(
                userId = user.id.value,
                isRead = null,
                page = 1,
                limit = 10
            )

            assertNotNull(response)
            assertEquals(2, response.notifications.items.size)
            assertEquals(2, response.unreadCount)
        }
    }

    @Test
    fun `읽지 않은 알림 개수 조회`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val follower = TestFixtures.createTestUser(email = "f@example.com", username = "f")

            notificationService.sendFollowNotification(follower.id.value, user.id.value)

            val response = notificationService.getUnreadCount(user.id.value)

            assertNotNull(response)
            assertEquals(1, response.count)
        }
    }

    // ===== 알림 관리 테스트 (5개) =====

    @Test
    fun `알림 읽음 처리 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val follower = TestFixtures.createTestUser(email = "f@example.com", username = "f")

            val notification = notificationService.sendFollowNotification(follower.id.value, user.id.value)

            notificationService.markAsRead(notification.id, user.id.value)

            val unreadCount = notificationService.getUnreadCount(user.id.value)
            assertEquals(0, unreadCount.count)
        }
    }

    @Test
    fun `알림 읽음 처리 실패 - 권한 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "u1@example.com", username = "u1")
            val user2 = TestFixtures.createTestUser(email = "u2@example.com", username = "u2")
            val follower = TestFixtures.createTestUser(email = "f@example.com", username = "f")

            val notification = notificationService.sendFollowNotification(follower.id.value, user1.id.value)

            assertFailsWith<NotFoundException> {
                notificationService.markAsRead(notification.id, user2.id.value)
            }
        }
    }

    @Test
    fun `모든 알림 읽음 처리 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val follower1 = TestFixtures.createTestUser(email = "f1@example.com", username = "f1")
            val follower2 = TestFixtures.createTestUser(email = "f2@example.com", username = "f2")

            notificationService.sendFollowNotification(follower1.id.value, user.id.value)
            notificationService.sendFollowNotification(follower2.id.value, user.id.value)

            notificationService.markAllAsRead(user.id.value)

            val unreadCount = notificationService.getUnreadCount(user.id.value)
            assertEquals(0, unreadCount.count)
        }
    }

    @Test
    fun `알림 삭제 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val follower = TestFixtures.createTestUser(email = "f@example.com", username = "f")

            val notification = notificationService.sendFollowNotification(follower.id.value, user.id.value)

            notificationService.deleteNotification(notification.id, user.id.value)

            val response = notificationService.getMyNotifications(
                userId = user.id.value,
                isRead = null,
                page = 1,
                limit = 10
            )

            assertEquals(0, response.notifications.items.size)
        }
    }

    @Test
    fun `범용 시스템 알림 전송 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val notification = notificationService.sendNotificationToUser(
                userId = user.id.value,
                type = NotificationType.SYSTEM_ANNOUNCEMENT,
                title = "시스템 공지",
                message = "정기 점검이 예정되어 있습니다",
                targetType = null,
                targetId = null
            )

            assertNotNull(notification)
            assertEquals(NotificationType.SYSTEM_ANNOUNCEMENT, notification.type)
            assertEquals(user.id.value, notification.recipientId)
        }
    }
}
