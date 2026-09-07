package com.ninezero.service

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.chat.data.ChatRepositoryImpl
import com.ninezero.features.chat.data.MessageRepositoryImpl
import com.ninezero.features.chat.domain.MessageService
import com.ninezero.features.chat.presentation.models.request.SendMessageRequest
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.domain.ProductService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MessageService 테스트
 *
 * 테스트 케이스: 16개
 * - 메시지 전송: 5개
 * - 메시지 조회: 4개
 * - 메시지 읽음 처리: 2개
 * - 메시지 삭제: 2개
 * - 메시지 검색: 4개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageServiceTest {

    private lateinit var messageService: MessageService
    private lateinit var messageRepository: MessageRepositoryImpl
    private lateinit var chatRepository: ChatRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var productRepository: ProductRepositoryImpl
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var blockedUserRepository: BlockedUserRepositoryImpl
    private lateinit var postService: PostService
    private lateinit var productService: ProductService
    private lateinit var notificationService: NotificationService
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var fileUploadService: FileUploadService
    private lateinit var imageProcessingService: ImageProcessingService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            messageRepository = MessageRepositoryImpl()
            chatRepository = ChatRepositoryImpl()
            userRepository = UserRepositoryImpl()
            productRepository = ProductRepositoryImpl()
            postRepository = PostRepositoryImpl()
            blockedUserRepository = BlockedUserRepositoryImpl()
            postService = mockk(relaxed = true)
            productService = mockk(relaxed = true)

            // Mock 객체 생성
            notificationService = mockk(relaxed = true)
            webSocketManager = mockk(relaxed = true)
            fileUploadService = mockk(relaxed = true)
            imageProcessingService = mockk(relaxed = true)

            // Mock 동작 정의
            coEvery {
                notificationService.sendChatMessageNotification(any(), any(), any(), any())
            } returns mockk()

            coEvery {
                webSocketManager.isUserConnected(any())
            } returns false

            coEvery {
                webSocketManager.sendToUser(any(), any())
            } returns true

            coEvery {
                postService.checkMultiplePostAccess(any(), any())
            } returns emptyMap()

            coEvery {
                fileUploadService.deleteFileIfSupabase(any())
            } returns true

            messageService = MessageService(
                messageRepository = messageRepository,
                chatRepository = chatRepository,
                userRepository = userRepository,
                productRepository = productRepository,
                postRepository = postRepository,
                postService = postService,
                blockedUserRepository = blockedUserRepository,
                productService = productService,
                notificationService = notificationService,
                webSocketManager = webSocketManager,
                fileUploadService = fileUploadService,
                imageProcessingService = imageProcessingService,
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

    // ===== 메시지 전송 테스트 (5개) =====

    @Test
    fun `메시지 전송 성공 - 텍스트 메시지`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            // 채팅방 생성
            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            val request = SendMessageRequest(
                content = "안녕하세요",
                chatMessageType = ChatMessageType.TEXT,
                mediaAttachments = null,
                productId = null
            )

            val response = messageService.sendMessage(chatRoom.id.value, user1.id.value, request)

            assertNotNull(response)
            assertEquals("안녕하세요", response.content)
            assertEquals(ChatMessageType.TEXT, response.chatMessageType)
            assertEquals(user1.id.value, response.sender.id)
        }
    }

    @Test
    fun `메시지 전송 성공 - 이미지 메시지`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            val request = SendMessageRequest(
                content = null,
                chatMessageType = ChatMessageType.IMAGE,
                mediaAttachments = listOf("https://example.com/image1.jpg"),
                productId = null
            )

            val response = messageService.sendMessage(chatRoom.id.value, user1.id.value, request)

            assertNotNull(response)
            assertEquals(ChatMessageType.IMAGE, response.chatMessageType)
            assertNotNull(response.mediaAttachments)
            assertEquals(1, response.mediaAttachments.size)
        }
    }

    @Test
    fun `메시지 전송 성공 - 상품 공유`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            val request = SendMessageRequest(
                content = "이 상품 어때요?",
                chatMessageType = ChatMessageType.PRODUCT_LINK,
                mediaAttachments = null,
                productId = product.id.value
            )

            val response = messageService.sendMessage(chatRoom.id.value, user1.id.value, request)

            assertNotNull(response)
            assertEquals(ChatMessageType.PRODUCT_LINK, response.chatMessageType)
            assertNotNull(response.productInfo)
            assertEquals(product.id.value, response.productInfo.productId)
        }
    }

    @Test
    fun `메시지 전송 실패 - 권한 없음 (채팅방에 속하지 않음)`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")

            // user1과 user2의 채팅방
            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            val request = SendMessageRequest(
                content = "안녕하세요",
                chatMessageType = ChatMessageType.TEXT,
                mediaAttachments = null,
                productId = null
            )

            // user3가 메시지 전송 시도
            assertFailsWith<ForbiddenException> {
                messageService.sendMessage(chatRoom.id.value, user3.id.value, request)
            }
        }
    }

    @Test
    fun `메시지 전송 실패 - 존재하지 않는 채팅방`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val request = SendMessageRequest(
                content = "안녕하세요",
                chatMessageType = ChatMessageType.TEXT,
                mediaAttachments = null,
                productId = null
            )

            assertFailsWith<NotFoundException> {
                messageService.sendMessage(99999, user.id.value, request)
            }
        }
    }

    // ===== 메시지 조회 테스트 (4개) =====

    @Test
    fun `메시지 목록 조회 성공 - 빈 목록`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            val response = messageService.getMessages(chatRoom.id.value, user1.id.value, 20)

            assertNotNull(response)
            assertEquals(0, response.messages.size)
            assertEquals(null, response.lastMessageId)
            assertEquals(false, response.hasNext)
        }
    }

    @Test
    fun `메시지 목록 조회 성공 - 여러 메시지`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            // 3개 메시지 전송
            repeat(3) { i ->
                messageService.sendMessage(
                    chatRoom.id.value,
                    user1.id.value,
                    SendMessageRequest("메시지 ${i + 1}", ChatMessageType.TEXT, null, null)
                )
            }

            val response = messageService.getMessages(chatRoom.id.value, user1.id.value, 20)

            assertNotNull(response)
            assertEquals(3, response.messages.size)
            assertNotNull(response.lastMessageId)
        }
    }

    @Test
    fun `메시지 목록 조회 - 페이지네이션`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            // 5개 메시지 전송
            repeat(5) { i ->
                messageService.sendMessage(
                    chatRoom.id.value,
                    user1.id.value,
                    SendMessageRequest("메시지 ${i + 1}", ChatMessageType.TEXT, null, null)
                )
            }

            // 첫 2개 조회
            val page1 = messageService.getMessages(chatRoom.id.value, user1.id.value, 2)
            assertEquals(2, page1.messages.size)
            assertTrue(page1.hasNext)

            // 다음 2개 조회
            val page2 = messageService.getMessages(
                chatRoom.id.value,
                user1.id.value,
                2,
                page1.lastMessageId
            )
            assertEquals(2, page2.messages.size)
            assertTrue(page2.hasNext)
        }
    }

    @Test
    fun `메시지 목록 조회 실패 - 권한 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            assertFailsWith<ForbiddenException> {
                messageService.getMessages(chatRoom.id.value, user3.id.value, 20)
            }
        }
    }

    @Test
    fun `메시지 목록 조회 - 상품 링크 차단 관계면 canAccess false`() {
        runBlocking {
            val viewer = TestFixtures.createTestUser(email = "viewer@example.com", username = "viewer")
            val sender = TestFixtures.createTestUser(email = "sender@example.com", username = "sender")
            val creator = TestFixtures.createTestCreator(email = "creator-link@example.com", username = "creatorlink")
            val product = TestFixtures.createTestProduct(creator.id.value)

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(viewer.id.value, sender.id.value)
            }

            coEvery { productService.getProductAccessMap(any(), any()) } returns mapOf(product.id.value to true)

            messageService.sendMessage(
                chatRoom.id.value,
                sender.id.value,
                SendMessageRequest(
                    content = "상품 링크",
                    chatMessageType = ChatMessageType.PRODUCT_LINK,
                    mediaAttachments = null,
                    productId = product.id.value
                )
            )

            query {
                blockedUserRepository.createBlock(viewer.id.value, creator.id.value)
            }

            val response = messageService.getMessages(chatRoom.id.value, viewer.id.value, 20)
            val productLink = response.messages.first { it.chatMessageType == ChatMessageType.PRODUCT_LINK }

            assertNotNull(productLink.productInfo)
            assertEquals(false, productLink.productInfo.canAccess)
        }
    }

    // ===== 메시지 읽음 처리 테스트 (2개) =====

    @Test
    fun `메시지 읽음 처리 성공`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            // user1이 메시지 전송
            messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("안녕하세요", ChatMessageType.TEXT, null, null)
            )

            // user2가 읽음 처리
            messageService.markMessagesAsRead(chatRoom.id.value, user2.id.value)

            // 읽지 않은 메시지 수 확인
            val room = query {
                chatRepository.findChatRoomById(chatRoom.id.value)
            }
            assertNotNull(room)
            assertEquals(0, room.user2UnreadCount)
        }
    }

    @Test
    fun `메시지 읽음 처리 실패 - 권한 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            assertFailsWith<ForbiddenException> {
                messageService.markMessagesAsRead(chatRoom.id.value, user3.id.value)
            }
        }
    }

    // ===== 메시지 삭제 테스트 (2개) =====

    @Test
    fun `메시지 삭제 성공`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            val message = messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("삭제할 메시지", ChatMessageType.TEXT, null, null)
            )

            // 메시지 삭제
            messageService.deleteMessage(message.id, user1.id.value)

            // 메시지가 soft delete 되었는지 확인 (isDeleted = true)
            val deletedMessage = query {
                messageRepository.findMessageById(message.id)
            }
            assertNotNull(deletedMessage)
            assertEquals(true, deletedMessage.isDeleted)
        }
    }

    @Test
    fun `메시지 삭제 실패 - 권한 없음 (다른 사용자의 메시지)`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            val message = messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("메시지", ChatMessageType.TEXT, null, null)
            )

            // user2가 user1의 메시지 삭제 시도
            assertFailsWith<ForbiddenException> {
                messageService.deleteMessage(message.id, user2.id.value)
            }
        }
    }

    // ===== 메시지 검색 테스트 (4개) =====

    @Test
    fun `메시지 검색 성공`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            // 여러 메시지 전송
            messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("안녕하세요", ChatMessageType.TEXT, null, null)
            )
            messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("안녕히 가세요", ChatMessageType.TEXT, null, null)
            )
            messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("좋은 하루", ChatMessageType.TEXT, null, null)
            )

            // "안녕" 검색
            val response = messageService.searchMessages(
                chatRoom.id.value,
                user1.id.value,
                "안녕",
                1,
                10
            )

            assertNotNull(response)
            assertEquals(2, response.items.size)
        }
    }

    @Test
    fun `메시지 검색 - 페이지네이션 메타데이터 정확성`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            // "안녕"이 포함된 메시지 2건 + 미포함 1건
            messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("안녕하세요", ChatMessageType.TEXT, null, null)
            )
            messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("안녕히 가세요", ChatMessageType.TEXT, null, null)
            )
            messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("좋은 하루", ChatMessageType.TEXT, null, null)
            )

            // limit=1로 검색하여 페이지네이션 메타데이터 검증
            val response = messageService.searchMessages(
                chatRoom.id.value,
                user1.id.value,
                "안녕",
                1,
                1
            )

            assertNotNull(response)
            assertEquals(1, response.items.size)
            assertEquals(2, response.totalCount)
            assertEquals(2, response.totalPages)
            assertTrue(response.hasNext)
        }
    }

    @Test
    fun `메시지 검색 - 결과 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("안녕하세요", ChatMessageType.TEXT, null, null)
            )

            // 존재하지 않는 키워드 검색
            val response = messageService.searchMessages(
                chatRoom.id.value,
                user1.id.value,
                "존재하지않는단어",
                1,
                10
            )

            assertNotNull(response)
            assertEquals(0, response.items.size)
        }
    }

    @Test
    fun `메시지 검색 실패 - 권한 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }

            assertFailsWith<ForbiddenException> {
                messageService.searchMessages(
                    chatRoom.id.value,
                    user3.id.value,
                    "안녕",
                    1,
                    10
                )
            }
        }
    }

    @Test
    fun `메시지 검색 - 포스트 링크 차단 관계면 canAccess false`() {
        runBlocking {
            val viewer = TestFixtures.createTestUser(email = "viewer2@example.com", username = "viewer2")
            val sender = TestFixtures.createTestUser(email = "sender2@example.com", username = "sender2")
            val postAuthor = TestFixtures.createTestUser(email = "author2@example.com", username = "author2")
            val post = TestFixtures.createTestPost(
                userId = postAuthor.id.value,
                content = "차단 테스트 포스트"
            )

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(viewer.id.value, sender.id.value)
            }

            query {
                messageRepository.createMessage(
                    roomId = chatRoom.id.value,
                    senderId = sender.id.value,
                    content = "포스트 링크",
                    chatMessageType = ChatMessageType.POST_LINK,
                    postId = post.id.value
                )
            }

            coEvery { postService.checkMultiplePostAccess(any(), any()) } returns mapOf(post.id.value to true)

            query {
                blockedUserRepository.createBlock(viewer.id.value, postAuthor.id.value)
            }

            val response = messageService.searchMessages(
                roomId = chatRoom.id.value,
                currentUserId = viewer.id.value,
                query = "포스트",
                page = 1,
                limit = 10
            )

            assertTrue(response.items.isNotEmpty())
            val postLink = response.items.first { it.chatMessageType == ChatMessageType.POST_LINK }
            assertNotNull(postLink.postInfo)
            assertEquals(false, postLink.postInfo.canAccess)
        }
    }
}
