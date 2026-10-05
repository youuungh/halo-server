package com.ninezero.service

import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.CreatorOnlyException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.TagNotFoundException
import com.ninezero.core.common.util.query
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

    // ===== 태그 생성 테스트 =====

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
                name = "",  // 빈 문자열
                targetType = TagTargetType.POST,
                isSectionEnabled = false
            )

            // When & Then
            assertFailsWith<InvalidInputException> {
                tagService.createTag(creator.id.value, request)
            }
        }
    }

    // ===== 태그 수정 테스트 =====

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
            assertEquals("수정된태그", response.name)
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
                name = "태그1",  // 이미 존재하는 이름
                isSectionEnabled = null
            )

            // When & Then
            assertFailsWith<ConflictException> {
                tagService.updateTag(tag2.id, creator.id.value, updateRequest)
            }
        }
    }

    // ===== 태그 삭제 테스트 =====

    @Test
    fun `태그 수정·삭제 실패 - 없는 태그`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()

            assertFailsWith<TagNotFoundException> {
                tagService.updateTag(999_999, creator.id.value, UpdateTagRequest(name = "새이름", isSectionEnabled = null))
            }
            assertFailsWith<TagNotFoundException> {
                tagService.deleteTag(999_999, creator.id.value)
            }
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

    @Test
    fun `삭제된 글에만 달린 태그는 사용 중이 아니라 개수 0이고 삭제된다`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val tag = tagService.createTag(
                creator.id.value,
                TagRequest(name = "지난공지", targetType = TagTargetType.POST, isSectionEnabled = false)
            )
            val deletedPost = TestFixtures.createTestPost(
                userId = creator.id.value,
                creatorId = creator.id.value,
                isActive = false
            )
            query { tagRepository.attachTagsToPost(deletedPost.id.value, listOf(tag.id)) }

            // When
            val listed = tagService.getCreatorTags(creator.id.value, TagTargetType.POST)
            tagService.deleteTag(tag.id, creator.id.value)

            // Then
            assertEquals(0, listed.tags.single().postCount)
            assertEquals(0, tagService.getCreatorTags(creator.id.value, TagTargetType.POST).tags.size)
        }
    }

    @Test
    fun `활성 글이 달린 태그는 삭제할 수 없다`() {
        runBlocking {
            // Given
            val creator = TestFixtures.createTestCreator()
            val tag = tagService.createTag(
                creator.id.value,
                TagRequest(name = "공지", targetType = TagTargetType.POST, isSectionEnabled = false)
            )
            val post = TestFixtures.createTestPost(userId = creator.id.value, creatorId = creator.id.value)
            query { tagRepository.attachTagsToPost(post.id.value, listOf(tag.id)) }

            // When & Then
            assertFailsWith<ConflictException> {
                tagService.deleteTag(tag.id, creator.id.value)
            }
        }
    }
}
