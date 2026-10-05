package com.ninezero.service

import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
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
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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

    // ===== 알림 전송 테스트 =====

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
    fun `같은 주문의 두 배송 그룹 완료 알림은 따로 쌓인다`() {
        runBlocking {
            val buyer = TestFixtures.createTestUser()
            val creatorA = TestFixtures.createTestCreator(email = "a@example.com", username = "creatora")
            val creatorB = TestFixtures.createTestCreator(email = "b@example.com", username = "creatorb")

            val first = notificationService.sendShippingDeliveredNotification(buyer.id.value, orderId = 1, creatorId = creatorA.id.value)
            val second = notificationService.sendShippingDeliveredNotification(buyer.id.value, orderId = 1, creatorId = creatorB.id.value)

            assertNotEquals(first.id, second.id)
        }
    }

    @Test
    fun `다른 주문에서 같은 금액이 적립되면 알림이 따로 쌓인다`() {
        runBlocking {
            val buyer = TestFixtures.createTestUser()

            val first = notificationService.sendPointEarnedNotification(buyer.id.value, orderId = 1, amount = BigDecimal("500"))
            val second = notificationService.sendPointEarnedNotification(buyer.id.value, orderId = 2, amount = BigDecimal("500"))

            assertNotEquals(first.id, second.id)
        }
    }

    @Test
    fun `같은 대상 알림은 문구가 달라야 새로 쌓인다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            suspend fun renewalNotice(nextBillingDate: LocalDate) = notificationService.sendSubscriptionRenewedNotification(
                userId = user.id.value,
                subscriptionId = 1,
                creatorUsername = "creator",
                planName = "베이직",
                nextBillingDate = nextBillingDate
            )

            val thisMonth = renewalNotice(LocalDate(2026, 10, 11))
            val thisMonthAgain = renewalNotice(LocalDate(2026, 10, 11))
            val nextMonth = renewalNotice(LocalDate(2026, 11, 10))

            assertEquals(thisMonth.id, thisMonthAgain.id)  // 같은 회차 재발송은 중복 제거
            assertNotEquals(thisMonth.id, nextMonth.id)  // 다음 달 갱신은 새 알림
        }
    }

    // ===== 알림 관리 테스트 =====

    @Test
    fun `채팅 메시지는 알림 목록과 안 읽은 수에 쌓이지 않고 접속 안 한 기기에 푸시만 보낸다`() {
        runBlocking {
            clearMocks(fcmService, answers = false)
            val sender = TestFixtures.createTestUser(email = "sender@example.com", username = "sender")
            val recipient = TestFixtures.createTestUser(email = "recipient@example.com", username = "recipient")
            query { userRepository.registerDeviceToken(recipient.id.value, "device-1", "fid-1") }

            notificationService.sendChatMessageNotification(
                recipientId = recipient.id.value,
                senderId = sender.id.value,
                roomId = 7,
                messagePreview = "안녕하세요"
            )

            assertEquals(0, notificationService.getMyNotifications(recipient.id.value, null, null, 10).notifications.items.size)
            assertEquals(0, notificationService.getUnreadCount(recipient.id.value).count)
            coVerify(exactly = 1) {
                fcmService.sendBatchPush(listOf("fid-1"), any(), any(), match { it["type"] == "CHAT_MESSAGE" && it["targetId"] == "7" })
            }
        }
    }

    @Test
    fun `글을 지우면 그 글의 새 글 알림이 비활성화된다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val follower = TestFixtures.createTestUser()
            val post = TestFixtures.createTestPost(userId = creator.id.value, creatorId = creator.id.value)
            notificationService.sendNewPostNotifications(
                creatorId = creator.id.value,
                creatorUsername = "creator",
                postId = post.id.value,
                followerIds = listOf(follower.id.value)
            )
            assertEquals(1, notificationService.getUnreadCount(follower.id.value).count)

            val deactivated = notificationService.deactivateNewPostNotifications(post.id.value)

            assertEquals(1, deactivated)
            assertEquals(0, notificationService.getUnreadCount(follower.id.value).count)
        }
    }

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
    fun `읽지 않은 알림이 없어도 모든 알림 읽음 처리는 성공`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            notificationService.markAllAsRead(user.id.value)
            notificationService.markAllAsRead(user.id.value)  // 연속 요청

            assertEquals(0, notificationService.getUnreadCount(user.id.value).count)
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
                cursor = null,
                limit = 10
            )

            assertEquals(0, response.notifications.items.size)
            assertEquals(0, response.unreadCount)
        }
    }
}
