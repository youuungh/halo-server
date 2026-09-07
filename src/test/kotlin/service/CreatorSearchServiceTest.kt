package com.ninezero.service

import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.exception.InvalidSearchQueryException
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.search.data.CreatorSearchRepositoryImpl
import com.ninezero.features.search.data.SearchHistoryRepositoryImpl
import com.ninezero.features.search.domain.CreatorSearchService
import com.ninezero.features.search.domain.SearchHistoryService
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CreatorSearchService 테스트
 *
 * 테스트 케이스: 9개
 * - 크리에이터 검색: 5개
 * - 인기 크리에이터 조회: 4개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreatorSearchServiceTest {

    private lateinit var creatorSearchService: CreatorSearchService
    private lateinit var creatorSearchRepository: CreatorSearchRepositoryImpl
    private lateinit var searchHistoryService: SearchHistoryService
    private lateinit var followRepository: FollowRepositoryImpl
    private lateinit var postRepository: PostRepositoryImpl
    private lateinit var productRepository: ProductRepositoryImpl
    private lateinit var blockedUserRepository: BlockedUserRepositoryImpl
    private lateinit var cacheService: CacheService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            creatorSearchRepository = CreatorSearchRepositoryImpl()
            followRepository = FollowRepositoryImpl()
            postRepository = PostRepositoryImpl()
            productRepository = ProductRepositoryImpl()
            blockedUserRepository = BlockedUserRepositoryImpl()
            cacheService = mockk(relaxed = true)

            // CacheService mock 설정
            coEvery { cacheService.get<Any>(any(), any()) } returns null

            val searchHistoryRepository = SearchHistoryRepositoryImpl()
            searchHistoryService = SearchHistoryService(searchHistoryRepository)

            creatorSearchService = CreatorSearchService(
                creatorSearchRepository = creatorSearchRepository,
                searchHistoryService = searchHistoryService,
                followRepository = followRepository,
                postRepository = postRepository,
                productRepository = productRepository,
                blockedUserRepository = blockedUserRepository,
                cacheService = cacheService
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

    // ===== 크리에이터 검색 테스트 (5개) =====

    @Test
    fun `크리에이터 검색 성공 - 사용자명으로 검색`() {
        runBlocking {
            // Given
            TestFixtures.createTestCreator(
                email = "creator1@test.com",
                username = "testcreator1"
            )
            TestFixtures.createTestCreator(
                email = "creator2@test.com",
                username = "testcreator2"
            )
            val viewer = TestFixtures.createTestUser(email = "viewer@test.com", username = "viewer")

            val keyword = "test"

            // When
            val response = creatorSearchService.searchCreators(
                keyword = keyword,
                currentUserId = viewer.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(2, response.items.size)
            assertTrue(response.items.any { it.username.contains("test") })
        }
    }

    @Test
    fun `크리에이터 검색 성공 - 팔로우 상태 포함`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val viewer = TestFixtures.createTestUser(email = "viewer@test.com", username = "viewer")

            // 팔로우
            TestFixtures.createTestFollow(followerId = viewer.id.value, followingId = creator.id.value)

            val keyword = creator.username

            // When
            val response = creatorSearchService.searchCreators(
                keyword = keyword,
                currentUserId = viewer.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(1, response.items.size)
            assertTrue(response.items[0].isFollowing)
        }
    }

    @Test
    fun `크리에이터 검색 성공 - 비로그인 사용자`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val keyword = creator.username

            // When - currentUserId = null
            val response = creatorSearchService.searchCreators(
                keyword = keyword,
                currentUserId = null,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(1, response.items.size)
            assertTrue(!response.items[0].isFollowing) // 비로그인은 항상 false
        }
    }

    @Test
    fun `크리에이터 검색 실패 - 빈 검색어`() {
        runBlocking {
            // Given — 현행 정책은 1자 검색 허용(MIN_KEYWORD_LENGTH=1), 빈 검색어만 차단
            val viewer = TestFixtures.createTestUser()
            val keyword = "   "

            // When & Then
            assertFailsWith<InvalidSearchQueryException> {
                creatorSearchService.searchCreators(
                    keyword = keyword,
                    currentUserId = viewer.id.value,
                    page = 1,
                    limit = 10
                )
            }
        }
    }

    @Test
    fun `크리에이터 검색 성공 - 결과 없음`() {
        runBlocking {
            // Given
            val viewer = TestFixtures.createTestUser()
            TestFixtures.createTestCreator() // 검색과 무관한 크리에이터

            val keyword = "존재하지않는크리에이터"

            // When
            val response = creatorSearchService.searchCreators(
                keyword = keyword,
                currentUserId = viewer.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(0, response.items.size)
            assertEquals(0, response.totalCount)
        }
    }

    // ===== 인기 크리에이터 조회 테스트 (3개) =====

    @Test
    fun `인기 크리에이터 조회 성공`() {
        runBlocking {
            // Given
            val creator1 = TestFixtures.createTestCreator(email = "creator1@test.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@test.com", username = "creator2")
            val follower = TestFixtures.createTestUser(email = "follower@test.com", username = "follower")
            val viewer = TestFixtures.createTestUser(email = "viewer@test.com", username = "viewer")

            // 팔로워 추가 (인기도 높이기) — viewer는 아무도 팔로우하지 않음
            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator1.id.value)
            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator2.id.value)

            // When
            val response = creatorSearchService.getPopularCreators(
                currentUserId = viewer.id.value,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(2, response.items.size)
            assertTrue(response.items.size <= 10)
        }
    }

    @Test
    fun `인기 크리에이터 조회 - 본인과 팔로우 중인 크리에이터 제외`() {
        runBlocking {
            // Given — creator1·creator2 모두 팔로워가 있어 인기 풀에 포함
            val creator1 = TestFixtures.createTestCreator(email = "creator1@test.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@test.com", username = "creator2")
            val follower = TestFixtures.createTestUser(email = "follower@test.com", username = "follower")
            val viewer = TestFixtures.createTestUser(email = "viewer@test.com", username = "viewer")

            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator1.id.value)
            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator2.id.value)

            // viewer는 creator1을 팔로우 중
            TestFixtures.createTestFollow(followerId = viewer.id.value, followingId = creator1.id.value)

            // When - 팔로우 중인 크리에이터 제외
            val viewerResponse = creatorSearchService.getPopularCreators(
                currentUserId = viewer.id.value,
                limit = 10
            )

            // Then — creator1(팔로우 중)은 제외되고 creator2만 반환
            assertEquals(listOf(creator2.id.value), viewerResponse.items.map { it.id })

            // When - 본인 제외 (creator1은 팔로워가 있어 풀에 포함되지만 본인 조회에선 제외)
            val selfResponse = creatorSearchService.getPopularCreators(
                currentUserId = creator1.id.value,
                limit = 10
            )

            // Then
            assertTrue(selfResponse.items.none { it.id == creator1.id.value })
        }
    }

    @Test
    fun `인기 크리에이터 조회 성공 - 비로그인 사용자`() {
        runBlocking {
            // Given
            val creator1 = TestFixtures.createTestCreator(email = "creator1@test.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@test.com", username = "creator2")
            val follower = TestFixtures.createTestUser(email = "follower@test.com", username = "follower")

            // 팔로워 추가해서 인기 크리에이터로 만들기
            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator1.id.value)
            TestFixtures.createTestFollow(followerId = follower.id.value, followingId = creator2.id.value)

            // When - currentUserId = null
            val response = creatorSearchService.getPopularCreators(
                currentUserId = null,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertTrue(response.items.isNotEmpty())
            // 비로그인은 모두 팔로우 안함
            assertTrue(response.items.all { !it.isFollowing })
        }
    }

    @Test
    fun `인기 크리에이터 조회 성공 - 빈 결과`() {
        runBlocking {
            // Given - 크리에이터 없음
            val viewer = TestFixtures.createTestUser()

            // When
            val response = creatorSearchService.getPopularCreators(
                currentUserId = viewer.id.value,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(0, response.items.size)
        }
    }
}