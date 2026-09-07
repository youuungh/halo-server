package com.ninezero.service

import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.features.search.data.SearchHistoryRepositoryImpl
import com.ninezero.features.search.domain.SearchHistoryService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SearchHistoryService 테스트
 *
 * 테스트 케이스: 10개
 * - 검색 기록 저장: 3개
 * - 같은 키워드 재검색(LRU): 2개
 * - 검색 기록 조회: 2개
 * - 검색 기록 삭제: 2개
 * - 검색 기록 전체 삭제: 1개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchHistoryServiceTest {

    private lateinit var searchHistoryService: SearchHistoryService
    private lateinit var searchHistoryRepository: SearchHistoryRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            searchHistoryRepository = SearchHistoryRepositoryImpl()

            searchHistoryService = SearchHistoryService(
                searchHistoryRepository = searchHistoryRepository
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

    // ===== 검색 기록 저장 테스트 (3개) =====

    @Test
    fun `검색 기록 저장 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val keyword = "테스트 검색"
            val searchType = "CREATOR"
            val resultCount = 5

            // When
            val result = searchHistoryService.saveHistory(
                userId = user.id.value,
                keyword = keyword,
                searchType = searchType,
                resultCount = resultCount
            )

            // Then
            assertTrue(result)

            // 조회로 확인
            val histories = searchHistoryService.getHistory(user.id.value)
            assertEquals(1, histories.items.size)
            assertEquals(keyword, histories.items[0].keyword)
        }
    }

    @Test
    fun `검색 기록 저장 실패 - 빈 키워드`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val keyword = ""

            // When
            val result = searchHistoryService.saveHistory(
                userId = user.id.value,
                keyword = keyword,
                searchType = "CREATOR",
                resultCount = 0
            )

            // Then
            assertTrue(!result) // 실패시 false 반환
        }
    }

    @Test
    fun `검색 기록 저장 실패 - 키워드 길이 초과`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val keyword = "a".repeat(101) // 최대 100자 초과

            // When
            val result = searchHistoryService.saveHistory(
                userId = user.id.value,
                keyword = keyword,
                searchType = "CREATOR",
                resultCount = 0
            )

            // Then
            assertTrue(!result) // 실패시 false 반환
        }
    }

    @Test
    fun `같은 키워드 재검색 - 중복 없이 맨 앞으로 올라온다`() {
        runBlocking {
            // Given - A, B, C 순으로 검색 (최신순이므로 C, B, A로 조회됨)
            val user = TestFixtures.createTestUser()
            listOf("A", "B", "C").forEach { keyword ->
                searchHistoryService.saveHistory(user.id.value, keyword, "CREATOR", 1)
            }

            // When - 가장 오래된 A를 다시 검색
            searchHistoryService.saveHistory(user.id.value, "A", "CREATOR", 7)

            // Then - 중복 행 없이 3건 유지 + A가 맨 앞
            val histories = searchHistoryService.getHistory(user.id.value)
            assertEquals(3, histories.items.size)
            assertEquals(listOf("A", "C", "B"), histories.items.map { it.keyword })
            // 재검색 시점의 resultCount로 갱신됐는지
            assertEquals(7, histories.items[0].resultCount)
        }
    }

    @Test
    fun `같은 키워드 재검색 - 기록 정원을 소모하지 않는다`() {
        runBlocking {
            // Given - 정원을 꽉 채운다
            val user = TestFixtures.createTestUser()
            val max = com.ninezero.core.common.config.Constants.Search.MAX_SEARCH_HISTORY
            repeat(max) { i ->
                searchHistoryService.saveHistory(user.id.value, "검색어$i", "CREATOR", 1)
            }

            // When - 이미 있는 키워드를 재검색 (새 항목이 아니므로 밀어낼 이유가 없다)
            searchHistoryService.saveHistory(user.id.value, "검색어0", "CREATOR", 1)

            // Then - 건수 그대로, 가장 오래된 항목도 살아있다
            val histories = searchHistoryService.getHistory(user.id.value, page = 1, limit = max)
            assertEquals(max, histories.items.size)
            assertTrue(histories.items.any { it.keyword == "검색어1" })
        }
    }

    // ===== 검색 기록 조회 테스트 (2개) =====

    @Test
    fun `검색 기록 조회 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // 검색 기록 3개 저장
            repeat(3) { i ->
                searchHistoryService.saveHistory(
                    userId = user.id.value,
                    keyword = "검색어${i + 1}",
                    searchType = "CREATOR",
                    resultCount = i + 1
                )
            }

            // When
            val response = searchHistoryService.getHistory(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(3, response.items.size)
            assertEquals(3, response.totalCount)
        }
    }

    @Test
    fun `검색 기록 조회 성공 - 빈 목록`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When - 검색 기록 없음
            val response = searchHistoryService.getHistory(user.id.value)

            // Then
            assertNotNull(response)
            assertEquals(0, response.items.size)
            assertEquals(0, response.totalCount)
        }
    }

    // ===== 검색 기록 삭제 테스트 (2개) =====

    @Test
    fun `검색 기록 개별 삭제 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // 검색 기록 저장
            searchHistoryService.saveHistory(
                userId = user.id.value,
                keyword = "삭제할 검색어",
                searchType = "CREATOR",
                resultCount = 1
            )

            val histories = searchHistoryService.getHistory(user.id.value)
            val historyId = histories.items[0].id

            // When
            searchHistoryService.deleteHistory(user.id.value, historyId)

            // Then - 삭제 확인
            val afterDelete = searchHistoryService.getHistory(user.id.value)
            assertEquals(0, afterDelete.items.size)
        }
    }

    @Test
    fun `검색 기록 개별 삭제 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val user1 = TestFixtures.createTestUser(email = "user1@test.com", username = "user1")
            val user2 = TestFixtures.createTestUser(email = "user2@test.com", username = "user2")

            // user1의 검색 기록 저장
            searchHistoryService.saveHistory(
                userId = user1.id.value,
                keyword = "user1 검색어",
                searchType = "CREATOR",
                resultCount = 1
            )

            val histories = searchHistoryService.getHistory(user1.id.value)
            val historyId = histories.items[0].id

            // When & Then - user2가 user1의 기록 삭제 시도
            assertFailsWith<ForbiddenException> {
                searchHistoryService.deleteHistory(user2.id.value, historyId)
            }
        }
    }

    // ===== 검색 기록 전체 삭제 테스트 (1개) =====

    @Test
    fun `검색 기록 전체 삭제 성공`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // 검색 기록 5개 저장
            repeat(5) { i ->
                searchHistoryService.saveHistory(
                    userId = user.id.value,
                    keyword = "검색어${i + 1}",
                    searchType = "CREATOR",
                    resultCount = i + 1
                )
            }

            // 저장 확인
            val beforeClear = searchHistoryService.getHistory(user.id.value)
            assertEquals(5, beforeClear.items.size)

            // When - 전체 삭제
            searchHistoryService.clearHistory(user.id.value)

            // Then - 전체 삭제 확인
            val afterClear = searchHistoryService.getHistory(user.id.value)
            assertEquals(0, afterClear.items.size)
        }
    }
}