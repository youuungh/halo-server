package com.ninezero.service

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.ReportTargetType
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.features.social.data.CommentRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.social.data.ReportRepositoryImpl
import com.ninezero.features.social.domain.ReportService
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportServiceTest {

    private lateinit var reportService: ReportService
    private lateinit var reportRepository: ReportRepositoryImpl
    private lateinit var cacheService: CacheService

    private val threshold = Constants.Social.AUTO_BLIND_REPORT_THRESHOLD

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()
            reportRepository = ReportRepositoryImpl()
            cacheService = mockk(relaxed = true)
            reportService = ReportService(
                reportRepository,
                UserRepositoryImpl(),
                PostRepositoryImpl(),
                CommentRepositoryImpl(),
                cacheService
            )
        }
    }

    @BeforeEach
    fun clean() {
        runBlocking { TestDatabase.clearAll() }
    }

    // ===== 신고 접수 =====

    @Test
    fun `같은 사용자가 다시 신고하면 409이고 신고 수는 그대로다`() {
        runBlocking {
            val author = TestFixtures.createTestUser(email = "author2@test.com", username = "author2")
            val reporter = TestFixtures.createTestUser(email = "reporter2@test.com", username = "reporter2")
            val post = TestFixtures.createTestPost(userId = author.id.value)

            reportService.report(reporter.id.value, ReportTargetType.POST, post.id.value)

            assertFailsWith<ConflictException> {
                reportService.report(reporter.id.value, ReportTargetType.POST, post.id.value)
            }

            assertEquals(1, query { reportRepository.countReports(ReportTargetType.POST, post.id.value) })
        }
    }

    @Test
    fun `자기 포스트는 신고할 수 없다`() {
        runBlocking {
            val author = TestFixtures.createTestUser(email = "author3@test.com", username = "author3")
            val post = TestFixtures.createTestPost(userId = author.id.value)

            assertFailsWith<InvalidInputException> {
                reportService.report(author.id.value, ReportTargetType.POST, post.id.value)
            }
        }
    }

    // ===== 자동 블라인드 =====

    @Test
    fun `서로 다른 신고자가 임계치에 도달하면 자동 블라인드된다`() {
        runBlocking {
            val author = TestFixtures.createTestUser(email = "author4@test.com", username = "author4")
            val post = TestFixtures.createTestPost(userId = author.id.value)

            var lastBlinded = false
            repeat(threshold) { i ->
                val reporter = TestFixtures.createTestUser(email = "auto$i@test.com", username = "auto$i")
                lastBlinded = reportService
                    .report(reporter.id.value, ReportTargetType.POST, post.id.value)
                    .blinded
            }

            assertTrue(lastBlinded, "임계치 도달 시 blinded=true 여야 한다")

            val reloaded = query { PostDao.findById(post.id.value) }!!
            assertTrue(reloaded.isBlinded)
            // 목록/단건 조회가 전부 isActive를 거르므로, 이 한 줄이 곧 "전 경로에서 사라짐"을 뜻한다
            assertFalse(reloaded.isActive)
        }
    }

    @Test
    fun `임계치 직전까지는 블라인드되지 않는다`() {
        runBlocking {
            val author = TestFixtures.createTestUser(email = "author5@test.com", username = "author5")
            val post = TestFixtures.createTestPost(userId = author.id.value)

            repeat(threshold - 1) { i ->
                val reporter = TestFixtures.createTestUser(email = "near$i@test.com", username = "near$i")
                reportService.report(reporter.id.value, ReportTargetType.POST, post.id.value)
            }

            val reloaded = query { PostDao.findById(post.id.value) }!!
            assertFalse(reloaded.isBlinded)
            assertTrue(reloaded.isActive)
        }
    }

    // ===== 관리자 조치 =====

    @Test
    fun `작성자가 지운 글과 댓글은 관리자가 블라인드할 수 없다`() {
        runBlocking {
            val author = TestFixtures.createTestUser(email = "author12@test.com", username = "author12")
            val post = TestFixtures.createTestPost(userId = author.id.value)
            val deletedPost = TestFixtures.createTestPost(userId = author.id.value)
            val deletedComment = TestFixtures.createTestComment(userId = author.id.value, postId = post.id.value)
            query {
                PostRepositoryImpl().deletePost(deletedPost.id.value)
                CommentDao.findById(deletedComment.id.value)!!.isActive = false
            }

            assertFailsWith<NotFoundException> { reportService.blind(ReportTargetType.POST, deletedPost.id.value) }
            assertFailsWith<NotFoundException> { reportService.blind(ReportTargetType.COMMENT, deletedComment.id.value) }
        }
    }

    @Test
    fun `관리자가 해제하면 다시 보이고 신고 기록은 남는다`() {
        runBlocking {
            val author = TestFixtures.createTestUser(email = "author7@test.com", username = "author7")
            val reporter = TestFixtures.createTestUser(email = "reporter7@test.com", username = "reporter7")
            val post = TestFixtures.createTestPost(userId = author.id.value)

            reportService.report(reporter.id.value, ReportTargetType.POST, post.id.value)
            reportService.blind(ReportTargetType.POST, post.id.value)
            reportService.unblind(ReportTargetType.POST, post.id.value)

            val reloaded = query { PostDao.findById(post.id.value) }!!
            assertFalse(reloaded.isBlinded)
            assertTrue(reloaded.isActive)
            assertTrue(reloaded.moderationLocked)

            // 감사 흔적 — 해제해도 신고 기록은 지우지 않는다
            assertEquals(1, query { reportRepository.countReports(ReportTargetType.POST, post.id.value) })
        }
    }

    @Test
    fun `해제된 글은 신고가 임계치를 넘어도 다시 자동 블라인드되지 않는다`() {
        runBlocking {
            val author = TestFixtures.createTestUser(email = "author8@test.com", username = "author8")
            val post = TestFixtures.createTestPost(userId = author.id.value)

            reportService.blind(ReportTargetType.POST, post.id.value)
            reportService.unblind(ReportTargetType.POST, post.id.value)

            repeat(threshold) { i ->
                val reporter = TestFixtures.createTestUser(email = "locked$i@test.com", username = "locked$i")
                reportService.report(reporter.id.value, ReportTargetType.POST, post.id.value)
            }

            val reloaded = query { PostDao.findById(post.id.value) }!!
            assertFalse(reloaded.isBlinded, "moderationLocked가 자동 블라인드를 막아야 한다")
            assertTrue(reloaded.isActive)
        }
    }

    @Test
    fun `댓글도 임계치 도달 시 자동 블라인드된다`() {
        runBlocking {
            val author = TestFixtures.createTestUser(email = "author9@test.com", username = "author9")
            val post = TestFixtures.createTestPost(userId = author.id.value)
            val comment = TestFixtures.createTestComment(userId = author.id.value, postId = post.id.value)

            repeat(threshold) { i ->
                val reporter = TestFixtures.createTestUser(email = "c$i@test.com", username = "c$i")
                reportService.report(reporter.id.value, ReportTargetType.COMMENT, comment.id.value)
            }

            val reloaded = query { CommentDao.findById(comment.id.value) }!!
            assertTrue(reloaded.isBlinded)
            assertFalse(reloaded.isActive)
        }
    }

    // ===== 댓글 수·캐시 반영 =====

    @Test
    fun `답글을 블라인드하면 댓글 수와 부모 답글 수에서 빠지고 해제하면 돌아온다`() {
        runBlocking {
            val author = TestFixtures.createTestUser(email = "author10@test.com", username = "author10")
            val post = TestFixtures.createTestPost(userId = author.id.value)
            val parent = TestFixtures.createTestComment(userId = author.id.value, postId = post.id.value)
            val reply = TestFixtures.createTestComment(
                userId = author.id.value,
                postId = post.id.value,
                parentCommentId = parent.id.value
            )
            query {
                repeat(2) { PostRepositoryImpl().incrementCommentCount(post.id.value) }
                CommentRepositoryImpl().incrementRepliesCount(parent.id.value)
            }
            suspend fun counts() = query {
                PostDao.findById(post.id.value)!!.commentCount to CommentDao.findById(parent.id.value)!!.replyCount
            }

            reportService.blind(ReportTargetType.COMMENT, reply.id.value)
            assertEquals(1 to 0, counts())

            reportService.unblind(ReportTargetType.COMMENT, reply.id.value)
            assertEquals(2 to 1, counts())
        }
    }

    @Test
    fun `블라인드하면 해당 포스트 캐시를 지운다`() {
        runBlocking {
            clearMocks(cacheService)
            val author = TestFixtures.createTestUser(email = "author11@test.com", username = "author11")
            val post = TestFixtures.createTestPost(userId = author.id.value)

            reportService.blind(ReportTargetType.POST, post.id.value)

            coVerify { cacheService.deletePattern(CacheKeys.Patterns.post(post.id.value)) }
            val reloaded = query { PostDao.findById(post.id.value) }!!
            assertTrue(reloaded.isBlinded)
            assertFalse(reloaded.isActive)
        }
    }

    // ===== 관리자 신고 대상 목록 =====

    @Test
    fun `신고 대상 목록은 0 이하 page와 음수 limit을 기본값으로 보정한다`() {
        runBlocking {
            val response = reportService.findReportedTargets(targetType = null, page = 0, limit = -5)

            assertEquals(1, response.page)
        }
    }
}
