package com.ninezero.service

import com.ninezero.core.common.config.PointType
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.InvalidPointAmountException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.point.PointHistoryDao
import com.ninezero.core.database.entities.point.PointHistoryTable
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.point.data.PointHistoryRepositoryImpl
import com.ninezero.features.point.data.PointRepositoryImpl
import com.ninezero.features.point.domain.PointService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.ktor.server.plugins.*
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.and
import org.junit.jupiter.api.*
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * PointService 테스트
 *
 * 테스트 케이스: 16개
 * - 계정 관리: 2개
 * - 포인트 적립: 2개
 * - 포인트 사용: 4개
 * - 포인트 환불: 1개
 * - 포인트 내역: 3개
 * - 입력 검증: 4개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointServiceTest {

    private lateinit var pointService: PointService
    private lateinit var notificationService: NotificationService

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            // Mock 객체 생성 (알림은 성공으로 가정)
            notificationService = mockk(relaxed = true)

            // Service 초기화
            pointService = PointService(
                pointRepository = PointRepositoryImpl(),
                pointHistoryRepository = PointHistoryRepositoryImpl(),
                notificationService = notificationService,
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

    // ===== 계정 관리 테스트 (2개) =====

    @Test
    fun `포인트 계정 생성 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When & Then - 예외가 발생하지 않으면 성공
            pointService.createPointAccount(user.id.value)

            // 생성 확인
            val balance = pointService.getBalance(user.id.value)
            assertEquals(BigDecimal(balance.balance).compareTo(BigDecimal.ZERO), 0)
        }
    }

    @Test
    fun `포인트 계정 중복 생성 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)

            // When & Then - ConflictException 발생
            assertFailsWith<ConflictException> {
                pointService.createPointAccount(user.id.value)
            }
        }
    }

    // ===== 포인트 적립 테스트 (2개) =====

    @Test
    fun `포인트 적립 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)
            val amount = BigDecimal("1000")

            // When
            pointService.earnPoints(
                userId = user.id.value,
                amount = amount,
                orderId = 1,
                description = "주문 적립"
            )

            // Then - 잔액 확인
            val balance = pointService.getBalance(user.id.value)
            assertEquals(BigDecimal(balance.balance).compareTo(BigDecimal("1000")), 0)
        }
    }

    @Test
    fun `포인트 적립 시 계정 자동 생성 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            // 포인트 계정 생성하지 않음

            // When
            pointService.earnPoints(
                userId = user.id.value,
                amount = BigDecimal("500"),
                orderId = 1,
                description = "주문 적립"
            )

            // Then - 계정이 자동 생성되고 잔액 확인
            val balance = pointService.getBalance(user.id.value)
            assertEquals(BigDecimal(balance.balance).compareTo(BigDecimal("500")), 0)
        }
    }

    // ===== 포인트 사용 테스트 (4개) =====

    @Test
    fun `포인트 사용 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)
            pointService.earnPoints(
                userId = user.id.value,
                amount = BigDecimal("5000"),
                orderId = 1,
                description = "주문 적립"
            )

            // When
            pointService.usePoints(
                userId = user.id.value,
                amount = BigDecimal("3000"),
                orderId = 2,
                orderAmount = BigDecimal("10000")
            )

            // Then - 잔액 확인
            val balance = pointService.getBalance(user.id.value)
            assertEquals(BigDecimal(balance.balance).compareTo(BigDecimal("2000")), 0)
        }
    }

    @Test
    fun `포인트 부족 시 사용 실패 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)
            pointService.earnPoints(
                userId = user.id.value,
                amount = BigDecimal("1000"),
                orderId = 1,
                description = "주문 적립"
            )

            // When & Then - BadRequestException 발생
            assertFailsWith<BadRequestException> {
                pointService.usePoints(
                    userId = user.id.value,
                    amount = BigDecimal("2000"),
                    orderId = 2,
                    orderAmount = BigDecimal("10000")
                )
            }
        }
    }

    @Test
    fun `포인트 최대 사용 제한 테스트 - 주문금액의 50퍼센트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)
            pointService.earnPoints(
                userId = user.id.value,
                amount = BigDecimal("10000"),
                orderId = 1,
                description = "주문 적립"
            )

            // When & Then - 10000원 주문에 6000원 사용 시도 (50% 초과)
            assertFailsWith<BadRequestException> {
                pointService.usePoints(
                    userId = user.id.value,
                    amount = BigDecimal("6000"),
                    orderId = 2,
                    orderAmount = BigDecimal("10000")
                )
            }
        }
    }

    @Test
    fun `포인트 사용 - 최소 금액 미만 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)
            pointService.earnPoints(
                userId = user.id.value,
                amount = BigDecimal("1000"),
                orderId = 1,
                description = "주문 적립"
            )

            // When & Then - 최소 사용 금액 100원 미만
            assertFailsWith<BadRequestException> {
                pointService.usePoints(
                    userId = user.id.value,
                    amount = BigDecimal("50"),
                    orderId = 2,
                    orderAmount = BigDecimal("10000")
                )
            }
        }
    }

    // ===== 포인트 환불 테스트 (1개) =====

    @Test
    fun `포인트 환불 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)
            pointService.earnPoints(
                userId = user.id.value,
                amount = BigDecimal("5000"),
                orderId = 1,
                description = "주문 적립"
            )
            pointService.usePoints(
                userId = user.id.value,
                amount = BigDecimal("3000"),
                orderId = 2,
                orderAmount = BigDecimal("10000")
            )

            // When
            pointService.refundPoints(
                userId = user.id.value,
                amount = BigDecimal("3000"),
                orderId = 2
            )

            // Then - 잔액 확인
            val balance = pointService.getBalance(user.id.value)
            assertEquals(BigDecimal(balance.balance).compareTo(BigDecimal("5000")), 0)
        }
    }

    // ===== 포인트 내역 조회 테스트 (3개) =====

    @Test
    fun `포인트 내역 조회 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)
            pointService.earnPoints(user.id.value, BigDecimal("1000"), 1, "적립1")
            pointService.earnPoints(user.id.value, BigDecimal("2000"), 2, "적립2")
            pointService.usePoints(user.id.value, BigDecimal("500"), 3, BigDecimal("5000"))

            // When
            val response = pointService.getHistory(
                userId = user.id.value,
                page = 1,
                limit = 10
            )

            // Then
            assertNotNull(response)
            assertEquals(3, response.items.size)
            assertEquals(3, response.totalCount)
        }
    }

    @Test
    fun `포인트 내역 유형 필터 조회 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)
            pointService.earnPoints(user.id.value, BigDecimal("1000"), 1, "적립1")
            pointService.earnPoints(user.id.value, BigDecimal("2000"), 2, "적립2")
            pointService.usePoints(user.id.value, BigDecimal("500"), 3, BigDecimal("5000"))

            // When - EARN 필터
            val earnOnly = pointService.getHistory(
                userId = user.id.value,
                page = 1,
                limit = 10,
                type = PointType.EARN
            )

            // Then - 적립 2건만, 카운트도 필터 기준
            assertEquals(2, earnOnly.items.size)
            assertEquals(2, earnOnly.totalCount)
            assertTrue(earnOnly.items.all { it.type == PointType.EARN.name })

            // When - USE 필터
            val useOnly = pointService.getHistory(
                userId = user.id.value,
                page = 1,
                limit = 10,
                type = PointType.USE
            )

            // Then
            assertEquals(1, useOnly.items.size)
            assertEquals(1, useOnly.totalCount)
            assertTrue(useOnly.items.all { it.type == PointType.USE.name })
        }
    }

    @Test
    fun `잔액 조회 시 계정 없으면 실패 테스트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When & Then - NotFoundException 발생
            assertFailsWith<NotFoundException> {
                pointService.getBalance(user.id.value)
            }
        }
    }

    // ===== 입력 검증 테스트 (4개) =====

    @Test
    fun `포인트 금액 검증 테스트 - 음수`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)

            // When & Then - InvalidPointAmountException 발생
            assertFailsWith<InvalidPointAmountException> {
                pointService.earnPoints(
                    userId = user.id.value,
                    amount = BigDecimal("-100"),
                    orderId = 1,
                    description = "음수 적립"
                )
            }
        }
    }

    @Test
    fun `포인트 금액 검증 테스트 - 최대값 초과`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)

            // When & Then - InvalidPointAmountException 발생
            assertFailsWith<InvalidPointAmountException> {
                pointService.earnPoints(
                    userId = user.id.value,
                    amount = BigDecimal("1000001"), // 최대 1,000,000
                    orderId = 1,
                    description = "초과 적립"
                )
            }
        }
    }

    // ===== 포인트 만료 테스트 (2개) — H4-01 무한 루프 회귀 =====

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `만료 처리 - 소진되어 잔액 부족한 적립분도 무한 루프 없이 만료일만 정리`() {
        runBlocking {
            // Given: 1000 적립 후 600 사용 -> 잔액 400 (만료 대상 1000보다 작음 = underwater)
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)
            pointService.earnPoints(user.id.value, BigDecimal("1000"), 1, "적립")
            pointService.usePoints(user.id.value, BigDecimal("600"), 2, BigDecimal("2000"))

            // EARN 내역 만료일을 과거로 backdate -> 만료 대상화
            val past = Clock.System.now().minus(365.days).toLocalDateTime(TimeZone.UTC)
            query {
                PointHistoryDao.find {
                    (PointHistoryTable.userId eq user.id.value) and (PointHistoryTable.type eq PointType.EARN)
                }.forEach { it.expiresAt = past }
            }

            // When: 수정 전이라면 여기서 무한 루프 -> @Timeout 실패
            val expiredCount = pointService.expirePoints()

            // Then: underwater 적립분은 차감 안 함(잔액 유지), 만료일은 null 정리되어 재조회 불가
            assertEquals(0, expiredCount)
            val balance = pointService.getBalance(user.id.value)
            assertEquals(0, BigDecimal(balance.balance).compareTo(BigDecimal("400")))
            val allCleared = query {
                PointHistoryDao.find {
                    (PointHistoryTable.userId eq user.id.value) and (PointHistoryTable.type eq PointType.EARN)
                }.all { it.expiresAt == null }
            }
            assertTrue(allCleared)
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `만료 처리 - 잔액 충분한 적립분은 정상 차감되고 종료`() {
        runBlocking {
            // Given: 1000 적립, 미사용 -> 잔액 1000
            val user = TestFixtures.createTestUser()
            pointService.createPointAccount(user.id.value)
            pointService.earnPoints(user.id.value, BigDecimal("1000"), 1, "적립")

            val past = Clock.System.now().minus(365.days).toLocalDateTime(TimeZone.UTC)
            query {
                PointHistoryDao.find {
                    (PointHistoryTable.userId eq user.id.value) and (PointHistoryTable.type eq PointType.EARN)
                }.forEach { it.expiresAt = past }
            }

            // When
            val expiredCount = pointService.expirePoints()

            // Then: 정상 만료 차감 + 종료
            assertEquals(1, expiredCount)
            val balance = pointService.getBalance(user.id.value)
            assertEquals(0, BigDecimal(balance.balance).compareTo(BigDecimal.ZERO))
        }
    }
}