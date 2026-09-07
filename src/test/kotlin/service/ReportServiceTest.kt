package com.ninezero.service

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.ReportTargetType
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.features.social.data.ReportRepositoryImpl
import com.ninezero.features.social.domain.ReportService
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ReportService 테스트
 *
 * 관리자 화면이 앱에 없고 웹 콘솔은 범위 밖이므로, 모더레이션 상태 전이는 여기서 증명한다.
 *
 * 테스트 케이스: 9개
 * - 신고 접수/중복/자기 글: 4개
 * - 자동 블라인드: 2개
 * - 관리자 수동 블라인드/해제 + lock: 3개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportServiceTest {

    private lateinit var reportService: ReportService
    private lateinit var reportRepository: ReportRepositoryImpl

    private val threshold = Constants.Social.AUTO_BLIND_REPORT_THRESHOLD

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()
            reportRepository = ReportRepositoryImpl()
            reportService = ReportService(reportRepository, UserRepositoryImpl())
        }
    }

    @BeforeEach
    fun clean() {
        runBlocking { TestDatabase.clearAll() }
    }

    // ===== 신고 접수 =====

    @Test
    fun 포스트를_신고하면_신고_수가_1_증가한다() = runBlocking {
        val author = TestFixtures.createTestUser(email = "author@test.com", username = "author")
        val reporter = TestFixtures.createTestUser(email = "reporter@test.com", username = "reporter")
        val post = TestFixtures.createTestPost(userId = author.id.value)

        val result = reportService.report(
            reporterId = reporter.id.value,
            targetType = ReportTargetType.POST,
            targetId = post.id.value
        )

        assertEquals(1, result.reportCount)
        assertFalse(result.blinded)
    }

    @Test
    fun 같은_사용자가_다시_신고하면_409이고_신고_수는_그대로다() = runBlocking {
        val author = TestFixtures.createTestUser(email = "author2@test.com", username = "author2")
        val reporter = TestFixtures.createTestUser(email = "reporter2@test.com", username = "reporter2")
        val post = TestFixtures.createTestPost(userId = author.id.value)

        reportService.report(reporter.id.value, ReportTargetType.POST, post.id.value)

        assertFailsWith<ConflictException> {
            reportService.report(reporter.id.value, ReportTargetType.POST, post.id.value)
        }

        assertEquals(1, query { reportRepository.countReports(ReportTargetType.POST, post.id.value) })
    }

    @Test
    fun 자기_포스트는_신고할_수_없다() = runBlocking {
        val author = TestFixtures.createTestUser(email = "author3@test.com", username = "author3")
        val post = TestFixtures.createTestPost(userId = author.id.value)

        assertFailsWith<InvalidInputException> {
            reportService.report(author.id.value, ReportTargetType.POST, post.id.value)
        }
        Unit
    }

    @Test
    fun 자기_자신은_신고할_수_없다() = runBlocking {
        val user = TestFixtures.createTestUser(email = "self@test.com", username = "selfuser")

        assertFailsWith<InvalidInputException> {
            reportService.report(user.id.value, ReportTargetType.USER, user.id.value)
        }
        Unit
    }

    // ===== 자동 블라인드 =====

    @Test
    fun 서로_다른_신고자가_임계치에_도달하면_자동_블라인드된다() = runBlocking {
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

    @Test
    fun 임계치_직전까지는_블라인드되지_않는다() = runBlocking {
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

    // ===== 관리자 조치 =====

    @Test
    fun 관리자는_임계치와_무관하게_즉시_블라인드할_수_있다() = runBlocking {
        val author = TestFixtures.createTestUser(email = "author6@test.com", username = "author6")
        val post = TestFixtures.createTestPost(userId = author.id.value)

        reportService.blind(ReportTargetType.POST, post.id.value)

        val reloaded = query { PostDao.findById(post.id.value) }!!
        assertTrue(reloaded.isBlinded)
        assertFalse(reloaded.isActive)
    }

    @Test
    fun 관리자가_해제하면_다시_보이고_신고_기록은_남는다() = runBlocking {
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

    @Test
    fun 해제된_글은_신고가_임계치를_넘어도_다시_자동_블라인드되지_않는다() = runBlocking {
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

    @Test
    fun 댓글도_임계치_도달_시_자동_블라인드된다() = runBlocking {
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
