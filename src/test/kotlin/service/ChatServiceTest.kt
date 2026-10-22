package com.ninezero.service

import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.features.chat.data.ChatRepositoryImpl
import com.ninezero.features.chat.domain.ChatService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.core.common.util.query
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatServiceTest {

    private lateinit var chatService: ChatService
    private lateinit var chatRepository: ChatRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var blockedUserRepository: BlockedUserRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            chatRepository = ChatRepositoryImpl()
            userRepository = UserRepositoryImpl()
            blockedUserRepository = BlockedUserRepositoryImpl()

            chatService = ChatService(
                chatRepository = chatRepository,
                userRepository = userRepository,
                blockedUserRepository = blockedUserRepository
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

    // ===== 채팅방 생성 테스트 =====

    @Test
    fun `채팅방 생성 - 순서가 바뀌어도 같은 채팅방 반환`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            // user1이 user2와 채팅방 생성
            val chatRoom1 = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            // user2가 user1과 채팅방 생성 (순서 반대)
            val chatRoom2 = chatService.getOrCreateChatRoom(user2.id.value, user1.id.value)

            assertEquals(chatRoom1.id, chatRoom2.id, "순서가 바뀌어도 같은 채팅방이 반환되어야 합니다")
        }
    }

    @Test
    fun `채팅방 생성 실패 - 자기 자신과 채팅`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            assertFailsWith<InvalidInputException> {
                chatService.getOrCreateChatRoom(user.id.value, user.id.value)
            }
        }
    }

    // ===== 채팅방 조회 테스트 =====

    @Test
    fun `채팅방 상세 조회 실패 - 권한 없음`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")

            // user1과 user2의 채팅방
            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            // user3가 조회 시도
            assertFailsWith<ForbiddenException> {
                chatService.getChatRoomById(chatRoom.id, user3.id.value)
            }
        }
    }

    @Test
    fun `채팅방 조회 - 방향별 차단 여부 포함`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)
            query { chatRepository.updateChatRoom(chatRoom.id, user1.id.value, "hi") }

            query { blockedUserRepository.createBlock(user1.id.value, user2.id.value) }

            val mine = chatService.getChatRoomById(chatRoom.id, user1.id.value)
            assertEquals(true, mine.isBlockedByMe)
            assertEquals(false, mine.isBlockedByOther)

            val theirs = chatService.getMyChatRooms(user2.id.value, false, 1, 10).items.single()
            assertEquals(false, theirs.isBlockedByMe)
            assertEquals(true, theirs.isBlockedByOther)

            val found = chatService.findChatRoomWithUser(user1.id.value, user2.id.value)
            assertEquals(true, found?.isBlockedByMe)
        }
    }

    // ===== 채팅방 보관 테스트 =====

    @Test
    fun `채팅방 보관 성공`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            // 보관 처리
            chatService.setArchived(chatRoom.id, user1.id.value, true)

            // 보관되지 않은 채팅방 목록에서 제외
            val activeRooms = chatService.getMyChatRooms(user1.id.value, false, 1, 10)
            assertEquals(0, activeRooms.items.size)

            // 보관된 채팅방 포함 목록에는 존재
            val allRooms = chatService.getMyChatRooms(user1.id.value, true, 1, 10)
            assertEquals(1, allRooms.items.size)
        }
    }

    // ===== 채팅방 사용자별 삭제 테스트 =====

    @Test
    fun `채팅방 삭제 - 본인 목록에서만 숨김`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)
            // 메시지가 한 번이라도 오갔다고 가정 (lastMessageAt 설정)
            query { chatRepository.updateChatRoom(chatRoom.id, 1, "hi") }

            // 약간의 시간차로 clearedAt > lastMessageAt 보장
            Thread.sleep(10)
            chatService.clearChatRoom(chatRoom.id, user1.id.value)

            // user1 목록에서는 사라짐
            val user1Rooms = chatService.getMyChatRooms(user1.id.value, false, 1, 10)
            assertEquals(0, user1Rooms.items.size, "삭제한 본인 목록에는 보이지 않아야 합니다")

            // user2 목록에는 그대로 남아 있음
            val user2Rooms = chatService.getMyChatRooms(user2.id.value, false, 1, 10)
            assertEquals(1, user2Rooms.items.size, "상대방 목록에는 영향이 없어야 합니다")
            assertEquals(chatRoom.id, user2Rooms.items.single().id)
        }
    }

    @Test
    fun `채팅방 삭제 후 상대방이 새 메시지 보내면 본인 목록에 복구`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)
            query { chatRepository.updateChatRoom(chatRoom.id, 1, "hi") }

            Thread.sleep(10)
            chatService.clearChatRoom(chatRoom.id, user1.id.value)

            // 사라졌는지 확인
            assertEquals(0, chatService.getMyChatRooms(user1.id.value, false, 1, 10).items.size)

            // user2가 새 메시지 보냄 → lastMessageAt 갱신
            Thread.sleep(10)
            query { chatRepository.updateChatRoom(chatRoom.id, 2, "hello again") }

            // user1 목록에 다시 나타남
            val rooms = chatService.getMyChatRooms(user1.id.value, false, 1, 10)
            assertEquals(1, rooms.items.size, "새 메시지로 복구되어야 합니다")
        }
    }

    @Test
    fun `채팅방 삭제 - unread count 0으로 초기화`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)
            assertEquals(0, chatService.getChatRoomById(chatRoom.id, user1.id.value).unreadCount)  // 새 방은 0부터
            query { chatRepository.updateChatRoom(chatRoom.id, 1, "hi") }
            query { chatRepository.incrementUnreadCount(chatRoom.id, user1.id.value) }
            query { chatRepository.incrementUnreadCount(chatRoom.id, user1.id.value) }

            // 사전 검증: unread = 2
            assertEquals(2, chatService.getChatRoomById(chatRoom.id, user1.id.value).unreadCount)

            Thread.sleep(10)
            chatService.clearChatRoom(chatRoom.id, user1.id.value)

            // unread count 0으로 초기화
            assertEquals(0, chatService.getChatRoomById(chatRoom.id, user1.id.value).unreadCount)
        }
    }

    @Test
    fun `채팅방 삭제 - 존재하지 않는 방은 NotFoundException`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            assertFailsWith<NotFoundException> {
                chatService.clearChatRoom(99999, user.id.value)
            }
        }
    }
}
