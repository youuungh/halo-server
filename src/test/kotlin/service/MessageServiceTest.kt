package com.ninezero.service

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.core.storage.StoragePaths
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.chat.data.ChatRepositoryImpl
import com.ninezero.features.chat.data.MessageRepositoryImpl
import com.ninezero.features.chat.domain.MessageService
import com.ninezero.features.chat.presentation.models.request.ChatUploadPrepareRequest
import com.ninezero.features.chat.presentation.models.request.SendMessageRequest
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.domain.ProductService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.upload.data.MediaUploadRepositoryImpl
import com.ninezero.features.upload.domain.MediaUploadService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.minusDays
import com.ninezero.helper.TestFixtures.plusDays
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    private lateinit var mediaUploadService: MediaUploadService

    private val mp4Head = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D, 0, 0, 0, 0)

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
            coJustRun {
                notificationService.sendChatMessageNotification(any(), any(), any(), any())
            }

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
                fileUploadService.deleteByUrls(any())
            } returns true

            // 직접 업로드 확인에 쓰는 스토리지 응답
            coEvery { fileUploadService.fileSize(any(), any()) } returns 1000L
            coEvery { fileUploadService.readHead(any(), any(), any()) } returns mp4Head

            mediaUploadService = MediaUploadService(
                mediaUploadRepository = MediaUploadRepositoryImpl(),
                fileUploadService = fileUploadService,
                imageProcessingService = ImageProcessingService()
            )

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
                mediaUploadService = mediaUploadService,
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

    // ===== 재전송 멱등 테스트 =====

    @Test
    fun `같은 clientTempId 재전송은 메시지를 새로 만들지 않음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val chatRoom = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }

            val request = SendMessageRequest(
                content = "안녕하세요",
                chatMessageType = ChatMessageType.TEXT,
                clientTempId = "temp-1"
            )

            val first = messageService.sendMessage(chatRoom.id.value, user1.id.value, request)
            val second = messageService.sendMessage(chatRoom.id.value, user1.id.value, request)

            val unread: Int = query {
                val room = chatRepository.findChatRoomById(chatRoom.id.value)!!
                if (room.user1Id == user2.id.value) room.user1UnreadCount else room.user2UnreadCount
            }

            assertEquals(first.id, second.id)
            assertEquals(1, unread)
        }
    }

    @Test
    fun `clientTempId가 비었거나 40자를 넘으면 전송을 거절한다`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val chatRoom = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }

            listOf("", "a".repeat(41)).forEach { tempId ->
                assertFailsWith<InvalidInputException> {
                    messageService.sendMessage(
                        chatRoom.id.value, user1.id.value,
                        SendMessageRequest(content = "안녕하세요", chatMessageType = ChatMessageType.TEXT, clientTempId = tempId)
                    )
                }
            }
            messageService.sendMessage(
                chatRoom.id.value, user1.id.value,
                SendMessageRequest(content = "안녕하세요", chatMessageType = ChatMessageType.TEXT, clientTempId = "a".repeat(40))
            )
        }
    }

    @Test
    fun `clientTempId 없으면 기존처럼 매번 새 메시지`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val chatRoom = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }

            val request = SendMessageRequest(content = "안녕하세요", chatMessageType = ChatMessageType.TEXT)

            val first = messageService.sendMessage(chatRoom.id.value, user1.id.value, request)
            val second = messageService.sendMessage(chatRoom.id.value, user1.id.value, request)

            assertTrue(first.id != second.id)
        }
    }

    // ===== 메시지 전송 테스트 =====

    @Test
    fun `메시지 전송 - 차단한 크리에이터의 상품 공유는 전송 응답에서도 canAccess false`() {
        runBlocking {
            val sender = TestFixtures.createTestUser(email = "sender@example.com", username = "sender")
            val receiver = TestFixtures.createTestUser(email = "receiver@example.com", username = "receiver")
            val creator = TestFixtures.createTestCreator(email = "creator-link@example.com", username = "creatorlink")
            val product = TestFixtures.createTestProduct(creator.id.value)
            val chatRoom = query { chatRepository.getOrCreateChatRoom(sender.id.value, receiver.id.value) }
            coEvery { productService.getProductAccessMap(any(), any()) } returns mapOf(product.id.value to true)
            query { blockedUserRepository.createBlock(sender.id.value, creator.id.value) }

            val response = messageService.sendMessage(
                chatRoom.id.value,
                sender.id.value,
                SendMessageRequest(
                    content = "상품 링크",
                    chatMessageType = ChatMessageType.PRODUCT_LINK,
                    productId = product.id.value
                )
            )

            // 새로고침한 목록과 같은 판정
            assertEquals(false, response.productInfo?.canAccess)
        }
    }

    @Test
    fun `사진 메시지는 이 방에 본인이 올린 사진만 보낼 수 있다`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }
            val roomId = chatRoom.id.value

            // 남의 포스트 파일, 같은 방 상대 파일, 외부 주소
            val foreignUrls = listOf(
                StoragePaths.publicUrl(StorageConfig.Buckets.POSTS, "posts/1/post_1_1_abc.jpg"),
                StoragePaths.publicUrl(StorageConfig.Buckets.CHAT_IMAGES, "chats/$roomId/chat_${user2.id.value}_1_abc.webp"),
                "https://evil.example.com/storage/v1/object/public/chat-images/chats/$roomId/chat_${user1.id.value}_1_abc.webp",
                // 본인 영상·파일이라도 사진 메시지엔 못 붙임
                StoragePaths.publicUrl(StorageConfig.Buckets.CHAT_FILES, "chats/$roomId/files/chat_file_${user1.id.value}_1_abc.pdf"),
                StoragePaths.publicUrl(StorageConfig.Buckets.CHAT_VIDEOS, "chats/$roomId/videos/chat_video_${user1.id.value}_1_abc.mp4")
            )

            foreignUrls.forEach { url ->
                assertFailsWith<InvalidInputException> {
                    messageService.sendMessage(
                        roomId,
                        user1.id.value,
                        SendMessageRequest(content = null, chatMessageType = ChatMessageType.IMAGE, mediaAttachments = listOf(url))
                    )
                }
            }

            val ownUrl = StoragePaths.publicUrl(StorageConfig.Buckets.CHAT_IMAGES, "chats/$roomId/chat_${user1.id.value}_1_abc.webp")
            val sent = messageService.sendMessage(
                roomId,
                user1.id.value,
                SendMessageRequest(content = null, chatMessageType = ChatMessageType.IMAGE, mediaAttachments = listOf(ownUrl))
            )
            assertEquals(ChatMessageType.IMAGE, sent.chatMessageType)
            assertEquals(listOf(ownUrl), sent.mediaAttachments)
        }
    }

    @Test
    fun `상품 공유 카드 - 타임딜 중이면 딜가와 딜 기준 할인율`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val creator = TestFixtures.createTestCreator()
            val product = TestFixtures.createTestProduct(creator.id.value)  // 판매가 10000, 정가 15000
            val now = nowUtc()
            query {
                ProductDao.findById(product.id.value)!!.apply {
                    dealPrice = BigDecimal("8000")
                    dealStartAt = now.minusDays(1)
                    dealEndAt = now.plusDays(1)
                }
            }
            val chatRoom = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }

            val response = messageService.sendMessage(
                chatRoom.id.value, user1.id.value,
                SendMessageRequest(content = "이 상품 어때요?", chatMessageType = ChatMessageType.PRODUCT_LINK, productId = product.id.value)
            )

            assertEquals(ChatMessageType.PRODUCT_LINK, response.chatMessageType)
            val info = assertNotNull(response.productInfo)
            assertEquals(product.id.value, info.productId)
            assertEquals(0, BigDecimal("8000").compareTo(BigDecimal(info.price)))
            assertEquals(0, BigDecimal("15000").compareTo(BigDecimal(info.originalPrice!!)))
            assertEquals(47, info.discountRate)
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

    // ===== 직접 업로드 테스트 =====

    @Test
    fun `영상 메시지 실패 - 업로드 ID 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val roomId = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }.id.value

            assertFailsWith<InvalidInputException> {
                messageService.sendMessage(roomId, user1.id.value, SendMessageRequest(chatMessageType = ChatMessageType.VIDEO))
            }
        }
    }

    @Test
    fun `영상 메시지 - 확인한 업로드의 공개 URL과 실제 크기로 저장`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val roomId = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }.id.value

            val prepared = messageService.prepareChatUpload(
                roomId, user1.id.value,
                ChatUploadPrepareRequest(messageType = ChatMessageType.VIDEO, mimeType = "video/mp4", size = 1000)
            )
            val response = messageService.sendMessage(
                roomId, user1.id.value,
                SendMessageRequest(chatMessageType = ChatMessageType.VIDEO, uploadId = prepared.uploadId)
            )

            val videoPrefix = StoragePaths.publicUrl(
                StorageConfig.Buckets.CHAT_VIDEOS,
                "chats/$roomId/videos/chat_video_${user1.id.value}_"
            )
            assertTrue(response.mediaAttachments?.single()?.startsWith(videoPrefix) == true)
            assertEquals(1000L, query { messageRepository.findMessageById(response.id) }?.fileSize)
        }
    }

    @Test
    fun `같은 clientTempId 재전송은 업로드를 다시 쓰지 않는다`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val roomId = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }.id.value

            val prepared = messageService.prepareChatUpload(
                roomId, user1.id.value,
                ChatUploadPrepareRequest(messageType = ChatMessageType.VIDEO, mimeType = "video/mp4", size = 1000)
            )
            val request = SendMessageRequest(
                chatMessageType = ChatMessageType.VIDEO,
                uploadId = prepared.uploadId,
                clientTempId = "video-1"
            )

            val first = messageService.sendMessage(roomId, user1.id.value, request)
            val second = messageService.sendMessage(roomId, user1.id.value, request)

            assertEquals(first.id, second.id)
        }
    }

    // ===== 메시지 조회 테스트 =====

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
            assertNotNull(page1.lastMessageId)

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

    // ===== 메시지 읽음 처리 테스트 =====

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

    // ===== 메시지 삭제 테스트 =====

    @Test
    fun `안 읽은 메시지를 지우면 수신자 안 읽음 수를 되돌린다`() {
        runBlocking {
            clearMocks(notificationService, answers = false)
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val chatRoom = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }

            val message = messageService.sendMessage(
                chatRoom.id.value,
                user1.id.value,
                SendMessageRequest("곧 지울 메시지", ChatMessageType.TEXT, null, null)
            )
            coVerify(timeout = 2000) {
                notificationService.sendChatMessageNotification(user2.id.value, user1.id.value, chatRoom.id.value, any())
            }

            messageService.deleteMessage(message.id, user1.id.value)

            val receiverUnread = query {
                val room = chatRepository.findChatRoomById(chatRoom.id.value)!!
                if (room.user1Id == user2.id.value) room.user1UnreadCount else room.user2UnreadCount
            }
            assertEquals(0, receiverUnread)
        }
    }

    @Test
    fun `이미 지운 메시지를 다시 지워도 수신자 안 읽음 수는 한 번만 줄어든다`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val chatRoom = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }

            messageService.sendMessage(chatRoom.id.value, user1.id.value, SendMessageRequest("남는 메시지", ChatMessageType.TEXT, null, null))
            val toDelete = messageService.sendMessage(chatRoom.id.value, user1.id.value, SendMessageRequest("지울 메시지", ChatMessageType.TEXT, null, null))

            messageService.deleteMessage(toDelete.id, user1.id.value)
            messageService.deleteMessage(toDelete.id, user1.id.value)  // 앱 재시도

            val receiverUnread = query {
                val room = chatRepository.findChatRoomById(chatRoom.id.value)!!
                if (room.user1Id == user2.id.value) room.user1UnreadCount else room.user2UnreadCount
            }
            assertEquals(1, receiverUnread)
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

    @Test
    fun `메시지 삭제 - 이 방에 본인이 올린 파일만 지운다`() {
        runBlocking {
            clearMocks(fileUploadService, answers = false)
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = query {
                chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value)
            }
            val roomId = chatRoom.id.value
            val ownImage = StoragePaths.publicUrl(StorageConfig.Buckets.CHAT_IMAGES, "chats/$roomId/chat_${user1.id.value}_1_a.webp")
            val ownThumb = StoragePaths.publicUrl(StorageConfig.Buckets.CHAT_IMAGES, "chats/$roomId/chat_thumb_${user1.id.value}_1_a.webp")
            val foreign = StoragePaths.publicUrl(StorageConfig.Buckets.POSTS, "posts/1/post_1_1_a.jpg")

            // 검증 도입 전에 저장된 데이터 재현
            val message = query {
                messageRepository.createMessage(
                    roomId = roomId,
                    senderId = user1.id.value,
                    content = null,
                    chatMessageType = ChatMessageType.IMAGE,
                    mediaAttachments = listOf(ownImage, foreign),
                    mediaThumbnails = listOf(ownThumb)
                )
            }

            messageService.deleteMessage(message.id.value, user1.id.value)

            val deleted = mutableListOf<List<String>>()
            coVerify { fileUploadService.deleteByUrls(capture(deleted)) }
            val deletedUrls = deleted.flatten()
            assertTrue(ownImage in deletedUrls)
            assertTrue(ownThumb in deletedUrls)
            assertFalse(foreign in deletedUrls)

            // 직접 업로드한 영상·파일 원본도 지움
            val ownVideo = StoragePaths.publicUrl(StorageConfig.Buckets.CHAT_VIDEOS, "chats/$roomId/videos/chat_video_${user1.id.value}_1_a.mp4")
            val ownFile = StoragePaths.publicUrl(StorageConfig.Buckets.CHAT_FILES, "chats/$roomId/files/chat_file_${user1.id.value}_1_a.pdf")
            listOf(ChatMessageType.VIDEO to ownVideo, ChatMessageType.FILE to ownFile).forEach { (type, url) ->
                val mediaMessage = query {
                    messageRepository.createMessage(
                        roomId = roomId,
                        senderId = user1.id.value,
                        content = null,
                        chatMessageType = type,
                        mediaAttachments = listOf(url)
                    )
                }
                messageService.deleteMessage(mediaMessage.id.value, user1.id.value)
                val mediaDeleted = mutableListOf<List<String>>()
                coVerify { fileUploadService.deleteByUrls(capture(mediaDeleted)) }
                assertTrue(url in mediaDeleted.flatten())
            }
        }
    }

    // ===== 메시지 검색 테스트 =====

    @Test
    fun `메시지 검색 - 검색어의 퍼센트와 밑줄은 글자 그대로 찾는다`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val chatRoom = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }

            messageService.sendMessage(chatRoom.id.value, user1.id.value, SendMessageRequest("100% 환불", ChatMessageType.TEXT, null, null))
            messageService.sendMessage(chatRoom.id.value, user1.id.value, SendMessageRequest("1000원 환불", ChatMessageType.TEXT, null, null))
            messageService.sendMessage(chatRoom.id.value, user1.id.value, SendMessageRequest("a1c 코드", ChatMessageType.TEXT, null, null))

            val percent = messageService.searchMessages(chatRoom.id.value, user1.id.value, "0%", 1, 10)
            val underscore = messageService.searchMessages(chatRoom.id.value, user1.id.value, "a_c", 1, 10)

            assertEquals(listOf("100% 환불"), percent.items.map { it.content })
            assertEquals(0, underscore.totalCount)
        }
    }

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

            assertEquals(2, response.items.size)
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

    @Test
    fun `없는 상품 id로 메시지를 보내면 404로 거절한다`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val chatRoom = query { chatRepository.getOrCreateChatRoom(user1.id.value, user2.id.value) }

            assertFailsWith<NotFoundException> {
                messageService.sendMessage(
                    chatRoom.id.value,
                    user1.id.value,
                    SendMessageRequest(
                        content = "이 상품 어때요?",
                        chatMessageType = ChatMessageType.PRODUCT_LINK,
                        mediaAttachments = null,
                        productId = 999_999
                    )
                )
            }
        }
    }
}
