package com.ninezero.service

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.features.chat.data.ChatRepositoryImpl
import com.ninezero.features.chat.data.MessageRepositoryImpl
import com.ninezero.features.chat.domain.MessageService
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.share.domain.ShareService
import com.ninezero.features.share.presentation.models.request.ShareProductRequest
import com.ninezero.features.share.presentation.models.request.SharePostRequest
import com.ninezero.core.common.util.query
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShareServiceTest {

    private lateinit var shareService: ShareService
    private lateinit var notificationService: NotificationService
    private lateinit var messageService: MessageService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            val postRepository = PostRepositoryImpl()
            val productRepository = ProductRepositoryImpl()
            val chatRepository = ChatRepositoryImpl()
            val messageRepository = MessageRepositoryImpl()
            val userRepository = UserRepositoryImpl()
            val followRepository = FollowRepositoryImpl()
            notificationService = mockk(relaxed = true)
            messageService = mockk(relaxed = true)

            coJustRun {
                notificationService.sendChatMessageNotification(any(), any(), any(), any())
            }

            shareService = ShareService(
                postRepository = postRepository,
                productRepository = productRepository,
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                notificationService = notificationService,
                userRepository = userRepository,
                followRepository = followRepository,
                blockedUserRepository = BlockedUserRepositoryImpl(),
                messageService = messageService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
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

    @Test
    fun `공유 메시지는 수신자에게 실시간으로 한 번만 전달된다`() {
        runBlocking {
            clearMocks(messageService, notificationService, answers = false)
            val sharer = TestFixtures.createTestUser(email = "s3@example.com", username = "s3")
            val recipient = TestFixtures.createTestUser(email = "s4@example.com", username = "s4")
            TestFixtures.createTestFollow(sharer.id.value, recipient.id.value)
            val post = TestFixtures.createTestPost(sharer.id.value)
            val request = SharePostRequest(
                postId = post.id.value,
                recipientUserIds = listOf(recipient.id.value),
                message = "이거 봐요",
                clientTempId = "share-live"
            )

            shareService.sharePost(sharer.id.value, request)
            shareService.sharePost(sharer.id.value, request)  // 같은 요청 재전송

            // 링크·텍스트 2개를 한 번에, 재전송은 다시 보내지 않음
            coVerify(exactly = 1, timeout = 2000) {
                messageService.pushToReceiver(recipient.id.value, match { it.size == 2 })
            }
            coVerify(exactly = 1) {
                notificationService.sendChatMessageNotification(recipient.id.value, sharer.id.value, any(), any())
            }
        }
    }

    @Test
    fun `같은 clientTempId 공유 재전송은 메시지·공유수를 늘리지 않음`() {
        runBlocking {
            val sharer = TestFixtures.createTestUser(email = "s1@example.com", username = "s1")
            val recipient = TestFixtures.createTestUser(email = "s2@example.com", username = "s2")
            TestFixtures.createTestFollow(sharer.id.value, recipient.id.value)
            val post = TestFixtures.createTestPost(sharer.id.value)

            val request = SharePostRequest(
                postId = post.id.value,
                recipientUserIds = listOf(recipient.id.value),
                message = "같이 봐요",
                clientTempId = "share-1"
            )

            val first = shareService.sharePost(sharer.id.value, request)
            val second = shareService.sharePost(sharer.id.value, request)

            val roomId = first.sharedRoomIds.first()
            val messageCount: Int = query {
                MessageRepositoryImpl().findMessagesByRoomId(roomId).size
            }
            val shareCount: Int = query {
                PostRepositoryImpl().findPostById(post.id.value)!!.shareCount
            }

            assertEquals(first.sharedRoomIds, second.sharedRoomIds)
            assertEquals(2, messageCount)  // 링크 + 텍스트
            assertEquals(1, shareCount)
        }
    }

    @Test
    fun `상품 공유 실패 - 삭제된 상품은 공유할 수 없음`() {
        runBlocking {
            val sharer = TestFixtures.createTestUser(email = "share-user@example.com", username = "shareuser")
            val recipient = TestFixtures.createTestUser(email = "share-target@example.com", username = "sharetarget")
            val creator = TestFixtures.createTestCreator(email = "share-creator@example.com", username = "sharecreator")
            val deletedProduct = TestFixtures.createTestProduct(
                creatorId = creator.id.value,
                isActive = false
            )

            val request = ShareProductRequest(
                productId = deletedProduct.id.value,
                recipientUserIds = listOf(recipient.id.value),
                message = "공유 테스트"
            )

            // findProductById가 soft-delete를 제외하므로 404(존재 은닉)로 떨어진다
            val exception = assertFailsWith<NotFoundException> {
                shareService.shareProduct(sharer.id.value, request)
            }

            assertEquals(Errors.Commerce.Product.PRODUCT_NOT_FOUND, exception.message)
        }
    }
}
