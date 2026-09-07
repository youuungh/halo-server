package com.ninezero.service

import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.CreatorOnlyException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.features.tag.data.TagRepositoryImpl
import com.ninezero.features.tag.domain.TagService
import com.ninezero.features.tag.presentation.models.request.TagRequest
import com.ninezero.features.tag.presentation.models.request.UpdateTagRequest
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TagService 테스트
 *
 * 테스트 케이스: 14개
 * - 태그 생성: 5개
 * - 태그 조회: 3개
 * - 태그 수정: 4개
 * - 태그 삭제: 2개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TagServiceTest {

    private lateinit var tagService: TagService
    private lateinit var tagRepository: TagRepositoryImpl
    private lateinit var userRepository: UserRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            tagRepository = TagRepositoryImpl()
            userRepository = UserRepositoryImpl()

            tagService = TagService(
                tagRepository = tagRepository,
                userRepository = userRepository
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

    // ===== 태그 생성 테스트 (5개) =====

    @Test
    fun `태그 생성 성공 - 포스트용 태그`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val request = TagRequest(
                name = "일상",
                targetType = TagTargetType.POST,
                isSectionEnabled = false
            )

            // When
            val response = tagService.createTag(creator.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals("일상", response.name)
            assertEquals(TagTargetType.POST, response.targetType)
            assertEquals(0, response.postCount)
        }
    }

    @Test
    fun `태그 생성 성공 - 상품용 태그`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val request = TagRequest(
                name = "피규어",
                targetType = TagTargetType.PRODUCT,
                isSectionEnabled = true
            )

            // When
            val response = tagService.createTag(creator.id.value, request)

            // Then
            assertNotNull(response)
            assertEquals("피규어", response.name)
            assertEquals(TagTargetType.PRODUCT, response.targetType)
            assertTrue(response.isSectionEnabled)
            assertEquals(0, response.productCount)
        }
    }

    @Test
    fun `태그 생성 실패 - 일반 사용자`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            val request = TagRequest(
                name = "태그",
                targetType = TagTargetType.POST,
                isSectionEnabled = false
            )

            // When & Then
            assertFailsWith<CreatorOnlyException> {
                tagService.createTag(user.id.value, request)
            }
        }
    }

    @Test
    fun `태그 생성 실패 - 중복된 태그명`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val request = TagRequest(
                name = "일상",
                targetType = TagTargetType.POST,
                isSectionEnabled = false
            )

            // 첫 번째 태그 생성
            tagService.createTag(creator.id.value, request)

            // When & Then - 같은 이름으로 다시 생성 시도
            assertFailsWith<ConflictException> {
                tagService.createTag(creator.id.value, request)
            }
        }
    }

    @Test
    fun `태그 생성 실패 - 유효하지 않은 태그명`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val request = TagRequest(
                name = "", // 빈 문자열
                targetType = TagTargetType.POST,
                isSectionEnabled = false
            )

            // When & Then
            assertFailsWith<InvalidInputException> {
                tagService.createTag(creator.id.value, request)
            }
        }
    }

    // ===== 태그 조회 테스트 (3개) =====

    @Test
    fun `크리에이터 태그 목록 조회 성공 - 포스트용`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()

            // 포스트용 태그 3개 생성
            repeat(3) { i ->
                tagService.createTag(
                    creator.id.value,
                    TagRequest(
                        name = "태그${i + 1}",
                        targetType = TagTargetType.POST,
                        isSectionEnabled = false
                    )
                )
            }

            // When
            val response = tagService.getCreatorTags(creator.id.value, TagTargetType.POST)

            // Then
            assertNotNull(response)
            assertEquals(3, response.tags.size)
            assertTrue(response.tags.all { it.targetType == TagTargetType.POST })
        }
    }

    @Test
    fun `크리에이터 태그 목록 조회 성공 - 상품용`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()

            // 상품용 태그 2개 생성
            repeat(2) { i ->
                tagService.createTag(
                    creator.id.value,
                    TagRequest(
                        name = "상품태그${i + 1}",
                        targetType = TagTargetType.PRODUCT,
                        isSectionEnabled = true
                    )
                )
            }

            // When
            val response = tagService.getCreatorTags(creator.id.value, TagTargetType.PRODUCT)

            // Then
            assertNotNull(response)
            assertEquals(2, response.tags.size)
            assertTrue(response.tags.all { it.targetType == TagTargetType.PRODUCT })
        }
    }

    @Test
    fun `크리에이터 태그 목록 조회 성공 - 빈 목록`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()

            // When - 태그 없음
            val response = tagService.getCreatorTags(creator.id.value, TagTargetType.POST)

            // Then
            assertNotNull(response)
            assertEquals(0, response.tags.size)
        }
    }

    // ===== 태그 수정 테스트 (4개) =====

    @Test
    fun `태그 수정 성공 - 이름 변경`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val tag = tagService.createTag(
                creator.id.value,
                TagRequest(
                    name = "원본태그",
                    targetType = TagTargetType.POST,
                    isSectionEnabled = false
                )
            )

            val updateRequest = UpdateTagRequest(
                name = "수정된태그",
                isSectionEnabled = null
            )

            // When
            val response = tagService.updateTag(tag.id, creator.id.value, updateRequest)

            // Then
            assertNotNull(response)
            assertEquals("수정된태그", response.name)
        }
    }

    @Test
    fun `태그 수정 성공 - 섹션 활성화 변경`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val tag = tagService.createTag(
                creator.id.value,
                TagRequest(
                    name = "태그",
                    targetType = TagTargetType.POST,
                    isSectionEnabled = false
                )
            )

            val updateRequest = UpdateTagRequest(
                name = null,
                isSectionEnabled = true
            )

            // When
            val response = tagService.updateTag(tag.id, creator.id.value, updateRequest)

            // Then
            assertNotNull(response)
            assertTrue(response.isSectionEnabled)
        }
    }

    @Test
    fun `태그 수정 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator1 = TestFixtures.createTestCreator(email = "creator1@test.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@test.com", username = "creator2")

            val tag = tagService.createTag(
                creator1.id.value,
                TagRequest(
                    name = "태그",
                    targetType = TagTargetType.POST,
                    isSectionEnabled = false
                )
            )

            val updateRequest = UpdateTagRequest(
                name = "수정시도",
                isSectionEnabled = null
            )

            // When & Then - 다른 크리에이터가 수정 시도
            assertFailsWith<CreatorOnlyException> {
                tagService.updateTag(tag.id, creator2.id.value, updateRequest)
            }
        }
    }

    @Test
    fun `태그 수정 실패 - 중복된 태그명으로 변경`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()

            // 태그 2개 생성
            tagService.createTag(
                creator.id.value,
                TagRequest(name = "태그1", targetType = TagTargetType.POST, isSectionEnabled = false)
            )
            val tag2 = tagService.createTag(
                creator.id.value,
                TagRequest(name = "태그2", targetType = TagTargetType.POST, isSectionEnabled = false)
            )

            val updateRequest = UpdateTagRequest(
                name = "태그1", // 이미 존재하는 이름
                isSectionEnabled = null
            )

            // When & Then
            assertFailsWith<ConflictException> {
                tagService.updateTag(tag2.id, creator.id.value, updateRequest)
            }
        }
    }

    // ===== 태그 삭제 테스트 (2개) =====

    @Test
    fun `태그 삭제 성공`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val tag = tagService.createTag(
                creator.id.value,
                TagRequest(
                    name = "삭제할태그",
                    targetType = TagTargetType.POST,
                    isSectionEnabled = false
                )
            )

            // When
            tagService.deleteTag(tag.id, creator.id.value)

            // Then - 조회 시 빈 목록
            val response = tagService.getCreatorTags(creator.id.value, TagTargetType.POST)
            assertEquals(0, response.tags.size)
        }
    }

    @Test
    fun `태그 삭제 실패 - 권한 없음`() {
        runBlocking {
            // Given
            val creator1 = TestFixtures.createTestCreator(email = "creator1@test.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@test.com", username = "creator2")

            val tag = tagService.createTag(
                creator1.id.value,
                TagRequest(
                    name = "태그",
                    targetType = TagTargetType.POST,
                    isSectionEnabled = false
                )
            )

            // When & Then - 다른 크리에이터가 삭제 시도
            assertFailsWith<CreatorOnlyException> {
                tagService.deleteTag(tag.id, creator2.id.value)
            }
        }
    }
}