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
import kotlin.test.assertTrue

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

    // ===== 크리에이터 검색 테스트 =====

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
            assertEquals(2, response.items.size)
            assertTrue(response.items.any { it.username.contains("test") })
        }
    }

    @Test
    fun `크리에이터 검색 실패 - 빈 검색어`() {
        runBlocking {
            // Given — 한 글자 검색은 허용하고 빈 검색어만 막는다
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

    // ===== 인기 크리에이터 조회 테스트 =====

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
}
