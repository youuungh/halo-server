package com.ninezero.service

import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.features.chat.data.ChatRepositoryImpl
import com.ninezero.features.chat.domain.ChatService
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.core.common.util.query
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.ktor.server.plugins.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ChatService 테스트
 *
 * 테스트 케이스: 15개
 * - 채팅방 생성: 4개
 * - 채팅방 조회: 4개
 * - 채팅방 참여: 3개
 * - 채팅방 보관: 4개
 * - 읽지 않은 메시지: 3개
 */
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

    // ===== 채팅방 생성 테스트 (4개) =====

    @Test
    fun `채팅방 생성 성공 - 새로운 채팅방`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            assertNotNull(chatRoom)
            assertNotNull(chatRoom.id)
            assertEquals(user2.username, chatRoom.otherUser.username)
        }
    }

    @Test
    fun `채팅방 생성 - 이미 존재하는 채팅방 반환`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            // 첫 번째 생성
            val chatRoom1 = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            // 두 번째 생성 시도 (같은 채팅방 반환)
            val chatRoom2 = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            assertEquals(chatRoom1.id, chatRoom2.id, "같은 채팅방이 반환되어야 합니다")
        }
    }

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

            assertFailsWith<BadRequestException> {
                chatService.getOrCreateChatRoom(user.id.value, user.id.value)
            }
        }
    }

    // ===== 채팅방 조회 테스트 (4개) =====

    @Test
    fun `내 채팅방 목록 조회 성공 - 빈 목록`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val response = chatService.getMyChatRooms(user.id.value, false, 1, 10)

            assertNotNull(response)
            assertEquals(0, response.items.size)
            assertEquals(0, response.totalCount)
        }
    }

    @Test
    fun `내 채팅방 목록 조회 성공 - 여러 채팅방`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")

            // user1이 user2, user3와 채팅방 생성
            chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)
            chatService.getOrCreateChatRoom(user1.id.value, user3.id.value)

            val response = chatService.getMyChatRooms(user1.id.value, false, 1, 10)

            assertNotNull(response)
            assertEquals(2, response.items.size)
            assertEquals(2, response.totalCount)
        }
    }

    @Test
    fun `채팅방 상세 조회 성공`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val created = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            val response = chatService.getChatRoomById(created.id, user1.id.value)

            assertNotNull(response)
            assertEquals(created.id, response.id)
            assertEquals(user2.username, response.otherUser.username)
        }
    }

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

    // ===== 채팅방 참여 테스트 (3개) =====

    @Test
    fun `양방향 채팅방 참여 확인`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            // 채팅방 생성
            chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            // user1의 채팅방 목록
            val user1Rooms = chatService.getMyChatRooms(user1.id.value, false, 1, 10)
            assertEquals(1, user1Rooms.items.size)

            // user2의 채팅방 목록
            val user2Rooms = chatService.getMyChatRooms(user2.id.value, false, 1, 10)
            assertEquals(1, user2Rooms.items.size)

            // 같은 채팅방
            assertEquals(user1Rooms.items.first().id, user2Rooms.items.first().id)
        }
    }

    @Test
    fun `여러 채팅방 참여 확인`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")
            val user4 = TestFixtures.createTestUser(email = "user4@example.com", username = "user4")

            // user1이 user2, user3, user4와 채팅방 생성
            chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)
            chatService.getOrCreateChatRoom(user1.id.value, user3.id.value)
            chatService.getOrCreateChatRoom(user1.id.value, user4.id.value)

            val rooms = chatService.getMyChatRooms(user1.id.value, false, 1, 10)

            assertEquals(3, rooms.items.size)
        }
    }

    @Test
    fun `채팅방 참여자 정보 확인`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            // user1 관점
            val room1 = chatService.getChatRoomById(chatRoom.id, user1.id.value)
            assertEquals(user2.username, room1.otherUser.username)

            // user2 관점
            val room2 = chatService.getChatRoomById(chatRoom.id, user2.id.value)
            assertEquals(user1.username, room2.otherUser.username)
        }
    }

    // ===== 채팅방 보관 테스트 (2개) =====

    @Test
    fun `채팅방 보관 성공`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            // 보관 처리
            chatService.toggleArchive(chatRoom.id, user1.id.value, true)

            // 보관되지 않은 채팅방 목록에서 제외
            val activeRooms = chatService.getMyChatRooms(user1.id.value, false, 1, 10)
            assertEquals(0, activeRooms.items.size)

            // 보관된 채팅방 포함 목록에는 존재
            val allRooms = chatService.getMyChatRooms(user1.id.value, true, 1, 10)
            assertEquals(1, allRooms.items.size)
        }
    }

    @Test
    fun `채팅방 보관 해제 성공`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            // 보관 처리
            chatService.toggleArchive(chatRoom.id, user1.id.value, true)

            // 보관 해제
            chatService.toggleArchive(chatRoom.id, user1.id.value, false)

            // 보관되지 않은 채팅방 목록에 다시 나타남
            val activeRooms = chatService.getMyChatRooms(user1.id.value, false, 1, 10)
            assertEquals(1, activeRooms.items.size)
        }
    }

    @Test
    fun `채팅방 보관 - 존재하지 않는 방은 NotFoundException`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")

            assertFailsWith<NotFoundException> {
                chatService.toggleArchive(99999, user1.id.value, true)
            }
        }
    }

    @Test
    fun `채팅방 보관 - 비참여자는 ForbiddenException`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            assertFailsWith<ForbiddenException> {
                chatService.toggleArchive(chatRoom.id, user3.id.value, true)
            }
        }
    }

    // ===== 채팅방 삭제 (per-user soft delete) 테스트 =====

    @Test
    fun `채팅방 삭제 - 본인 목록에서만 숨김`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)
            // 메시지가 한 번이라도 오갔다고 가정 (lastMessageAt 설정)
            query { chatRepository.updateChatRoom(chatRoom.id, 1, "hi", user2.id.value) }

            // 약간의 시간차로 clearedAt > lastMessageAt 보장
            Thread.sleep(10)
            chatService.clearChatRoom(chatRoom.id, user1.id.value)

            // user1 목록에서는 사라짐
            val user1Rooms = chatService.getMyChatRooms(user1.id.value, false, 1, 10)
            assertEquals(0, user1Rooms.items.size, "삭제한 본인 목록에는 보이지 않아야 합니다")

            // user2 목록에는 그대로 남아 있음
            val user2Rooms = chatService.getMyChatRooms(user2.id.value, false, 1, 10)
            assertEquals(1, user2Rooms.items.size, "상대방 목록에는 영향이 없어야 합니다")
        }
    }

    @Test
    fun `채팅방 삭제 후 상대방이 새 메시지 보내면 본인 목록에 복구`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)
            query { chatRepository.updateChatRoom(chatRoom.id, 1, "hi", user2.id.value) }

            Thread.sleep(10)
            chatService.clearChatRoom(chatRoom.id, user1.id.value)

            // 사라졌는지 확인
            assertEquals(0, chatService.getMyChatRooms(user1.id.value, false, 1, 10).items.size)

            // user2가 새 메시지 보냄 → lastMessageAt 갱신
            Thread.sleep(10)
            query { chatRepository.updateChatRoom(chatRoom.id, 2, "hello again", user2.id.value) }

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
            query { chatRepository.updateChatRoom(chatRoom.id, 1, "hi", user2.id.value) }
            query { chatRepository.incrementUnreadCount(chatRoom.id, user1.id.value) }
            query { chatRepository.incrementUnreadCount(chatRoom.id, user1.id.value) }

            // 사전 검증: unread = 2
            assertEquals(2, chatService.getUnreadCount(user1.id.value).totalUnreadCount)

            Thread.sleep(10)
            chatService.clearChatRoom(chatRoom.id, user1.id.value)

            // unread count 0으로 초기화
            assertEquals(0, chatService.getUnreadCount(user1.id.value).totalUnreadCount)
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

    @Test
    fun `채팅방 삭제 - 비참여자는 ForbiddenException`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")

            val chatRoom = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            assertFailsWith<ForbiddenException> {
                chatService.clearChatRoom(chatRoom.id, user3.id.value)
            }
        }
    }

    // ===== 읽지 않은 메시지 테스트 (2개) =====

    @Test
    fun `읽지 않은 메시지 수 조회 - 초기 상태`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")

            // 채팅방 생성
            chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)

            // 읽지 않은 메시지 수 조회
            val unreadCount = chatService.getUnreadCount(user1.id.value)

            assertNotNull(unreadCount)
            assertEquals(0, unreadCount.totalUnreadCount)
        }
    }

    @Test
    fun `읽지 않은 메시지 수 조회 - 여러 채팅방`() {
        runBlocking {
            val user1 = TestFixtures.createTestUser(email = "user1@example.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@example.com", username = "user2")
            val user3 = TestFixtures.createTestUser(email = "user3@example.com", username = "user3")

            // 여러 채팅방 생성
            val room1 = chatService.getOrCreateChatRoom(user1.id.value, user2.id.value)
            val room2 = chatService.getOrCreateChatRoom(user1.id.value, user3.id.value)

            // 두 방 모두 unread 증가
            query { chatRepository.incrementUnreadCount(room1.id, user1.id.value) }
            query { chatRepository.incrementUnreadCount(room2.id, user1.id.value) }

            // 읽지 않은 메시지 수 조회
            val unreadCount = chatService.getUnreadCount(user1.id.value)

            assertNotNull(unreadCount)
            assertNotNull(unreadCount.roomUnreadCounts)
            assertEquals(2, unreadCount.roomUnreadCounts.size)
            assertEquals(2, unreadCount.totalUnreadCount)
        }
    }

    @Test
    fun `읽지 않은 메시지 수 조회 - 101개 방 일관성`() {
        runBlocking {
            val mainUser = TestFixtures.createTestUser(email = "main@example.com", username = "mainuser")
            val otherUsers = TestFixtures.createTestUsers(101)

            // 101개 채팅방 생성 후 각 방에 unread 1씩 증가
            val roomIds = otherUsers.map { otherUser ->
                val room = query {
                    chatRepository.getOrCreateChatRoom(mainUser.id.value, otherUser.id.value)
                }
                query { chatRepository.incrementUnreadCount(room.id.value, mainUser.id.value) }
                room.id.value
            }

            val unreadCount = chatService.getUnreadCount(mainUser.id.value)

            // 101개 방 모두 unread 맵에 포함
            assertEquals(101, unreadCount.roomUnreadCounts.size)
            // 합계 일치
            assertEquals(unreadCount.totalUnreadCount, unreadCount.roomUnreadCounts.values.sum())
            // 모든 방이 포함되어 있는지 확인
            assertTrue(roomIds.all { it in unreadCount.roomUnreadCounts })
        }
    }
}