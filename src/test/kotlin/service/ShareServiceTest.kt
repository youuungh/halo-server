package com.ninezero.service

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.features.chat.data.ChatRepositoryImpl
import com.ninezero.features.chat.data.MessageRepositoryImpl
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.share.domain.ShareService
import com.ninezero.features.share.presentation.models.request.ShareProductRequest
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
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
            val notificationService = mockk<NotificationService>(relaxed = true)

            coEvery {
                notificationService.sendChatMessageNotification(any(), any(), any(), any())
            } returns null

            shareService = ShareService(
                postRepository = postRepository,
                productRepository = productRepository,
                chatRepository = chatRepository,
                messageRepository = messageRepository,
                notificationService = notificationService,
                userRepository = userRepository,
                followRepository = followRepository,
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
