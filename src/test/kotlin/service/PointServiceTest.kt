package com.ninezero.service

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.PointType
import com.ninezero.core.common.exception.InvalidPointException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.point.PointDao
import com.ninezero.core.database.entities.point.PointHistoryDao
import com.ninezero.core.database.entities.point.PointHistoryTable
import com.ninezero.core.database.entities.point.PointTable
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.point.data.PointHistoryRepositoryImpl
import com.ninezero.features.point.data.PointRepositoryImpl
import com.ninezero.features.point.domain.PointService
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.spyk
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.dao.EntityChangeType
import org.jetbrains.exposed.dao.EntityHook
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.*
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

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

    // ===== 동시성·멱등 테스트 =====

    @Test
    fun `동시 포인트 사용 - 잔액 초과 방지`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query {
                pointService.earnPointsInTransaction(
                    userId = user.id.value,
                    amount = BigDecimal("5000"),
                    orderId = 1,
                    description = "적립"
                )
            }

            val results = listOf(
                async(Dispatchers.IO) {
                    runCatching {
                        query { pointService.usePointsInTransaction(user.id.value, BigDecimal("3000"), 2, BigDecimal("10000")) }
                    }
                },
                async(Dispatchers.IO) {
                    runCatching {
                        query { pointService.usePointsInTransaction(user.id.value, BigDecimal("3000"), 3, BigDecimal("10000")) }
                    }
                }
            ).awaitAll()

            val successes = results.count { it.isSuccess }
            val balance = pointService.getBalance(user.id.value)
            assertEquals(1, successes)
            assertEquals(0, BigDecimal(balance.balance).compareTo(BigDecimal("2000")))
        }
    }

    @Test
    fun `동시 적립 - 같은 주문 이중 적립 방지`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }

            val results = listOf(
                async(Dispatchers.IO) {
                    runCatching {
                        query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 77, "주문 적립") }
                    }
                },
                async(Dispatchers.IO) {
                    runCatching {
                        query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 77, "주문 적립") }
                    }
                }
            ).awaitAll()

            val earnRows = query {
                PointHistoryDao.find {
                    (PointHistoryTable.orderId eq 77) and (PointHistoryTable.type eq PointType.EARN)
                }.count()
            }
            val balance = pointService.getBalance(user.id.value)

            assertEquals(2, results.count { it.isSuccess })
            assertEquals(1L, earnRows)
            assertEquals(0, BigDecimal(balance.balance).compareTo(BigDecimal("1000")))
        }
    }

    @Test
    fun `이미 적립된 주문 재적립은 0 반환`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }

            val first = query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1500"), 78, "주문 적립") }
            val second = query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1500"), 78, "주문 적립") }

            assertEquals(0, first.compareTo(BigDecimal("1500")))
            assertEquals(0, second.compareTo(BigDecimal.ZERO))
        }
    }

    // ===== 계정 관리 테스트 =====

    @Test
    fun `포인트 계정 중복 생성`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }

            // When
            query { pointService.createPointAccountInTransaction(user.id.value) }

            // Then - 두 번 불러도 계정은 하나
            val accountCount = query { PointDao.find { PointTable.userId eq user.id.value }.count() }
            assertEquals(1L, accountCount)
        }
    }

    // ===== 포인트 적립 테스트 =====

    @Test
    fun `포인트 적립 시 계정 자동 생성`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            // 포인트 계정 생성하지 않음

            // When
            query {
                pointService.earnPointsInTransaction(
                    userId = user.id.value,
                    amount = BigDecimal("500"),
                    orderId = 1,
                    description = "주문 적립"
                )
            }

            // Then - 계정이 자동 생성되고 잔액 확인
            val balance = pointService.getBalance(user.id.value)
            assertEquals(0, BigDecimal(balance.balance).compareTo(BigDecimal("500")))
        }
    }

    // ===== 포인트 사용 테스트 =====

    @Test
    fun `포인트 부족 시 사용 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query {
                pointService.earnPointsInTransaction(
                    userId = user.id.value,
                    amount = BigDecimal("1000"),
                    orderId = 1,
                    description = "주문 적립"
                )
            }

            // When & Then - InvalidPointException 발생
            assertFailsWith<InvalidPointException> {
                query {
                    pointService.usePointsInTransaction(
                        userId = user.id.value,
                        amount = BigDecimal("2000"),
                        orderId = 2,
                        orderAmount = BigDecimal("10000")
                    )
                }
            }
        }
    }

    @Test
    fun `포인트 최대 사용 제한 테스트 - 주문금액의 50퍼센트`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query {
                pointService.earnPointsInTransaction(
                    userId = user.id.value,
                    amount = BigDecimal("10000"),
                    orderId = 1,
                    description = "주문 적립"
                )
            }

            // When & Then - 10000원 주문에 6000원 사용 시도 (50% 초과)
            assertFailsWith<InvalidPointException> {
                query {
                    pointService.usePointsInTransaction(
                        userId = user.id.value,
                        amount = BigDecimal("6000"),
                        orderId = 2,
                        orderAmount = BigDecimal("10000")
                    )
                }
            }
        }
    }

    @Test
    fun `포인트 사용 - 최소 금액 미만`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query {
                pointService.earnPointsInTransaction(
                    userId = user.id.value,
                    amount = BigDecimal("1000"),
                    orderId = 1,
                    description = "주문 적립"
                )
            }

            // When & Then - 최소 사용 금액 100원 미만
            assertFailsWith<InvalidPointException> {
                query {
                    pointService.usePointsInTransaction(
                        userId = user.id.value,
                        amount = BigDecimal("50"),
                        orderId = 2,
                        orderAmount = BigDecimal("10000")
                    )
                }
            }
        }
    }

    // ===== 포인트 환불 테스트 =====

    @Test
    fun `포인트 환불`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query {
                pointService.earnPointsInTransaction(
                    userId = user.id.value,
                    amount = BigDecimal("5000"),
                    orderId = 1,
                    description = "주문 적립"
                )
            }
            query {
                pointService.usePointsInTransaction(
                    userId = user.id.value,
                    amount = BigDecimal("3000"),
                    orderId = 2,
                    orderAmount = BigDecimal("10000")
                )
            }

            // When
            query {
                pointService.refundPointsInTransaction(
                    userId = user.id.value,
                    amount = BigDecimal("3000"),
                    orderId = 2
                )
            }

            // Then - 잔액 확인
            val balance = pointService.getBalance(user.id.value)
            assertEquals(0, BigDecimal(balance.balance).compareTo(BigDecimal("5000")))
        }
    }

    // ===== 포인트 내역 조회 테스트 =====

    @Test
    fun `포인트 내역 기간 조회는 한국 날짜 경계로 자른다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 1, "적립") }
            // UTC 9월 30일 16시 = 한국 10월 1일 01시
            query {
                PointHistoryTable.update({ PointHistoryTable.userId eq user.id.value }) {
                    it[createdAt] = LocalDateTime(2026, 9, 30, 16, 0)
                }
            }

            // 앱은 한국 달의 1일 00:00:00 ~ 말일 23:59:59를 보냄
            val october = pointService.getHistory(
                user.id.value, 1, 10,
                startDate = LocalDateTime(2026, 10, 1, 0, 0),
                endDate = LocalDateTime(2026, 10, 31, 23, 59, 59)
            )
            val september = pointService.getHistory(
                user.id.value, 1, 10,
                startDate = LocalDateTime(2026, 9, 1, 0, 0),
                endDate = LocalDateTime(2026, 9, 30, 23, 59, 59)
            )

            assertEquals(1, october.totalCount)
            assertEquals(0, september.totalCount)
        }
    }

    @Test
    fun `포인트 내역 조회 - 0 이하 page는 보정하고 limit은 최대치로 제한`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query {
                repeat(Constants.MAX_PAGE_LIMIT + 1) { i ->
                    pointService.earnPointsInTransaction(user.id.value, BigDecimal("10"), i + 1, "적립$i")
                }
            }

            val corrected = pointService.getHistory(userId = user.id.value, page = 0, limit = 0)
            val capped = pointService.getHistory(userId = user.id.value, page = 1, limit = 5000)

            assertEquals(1, corrected.page)
            assertEquals(Constants.MAX_PAGE_LIMIT, capped.items.size)
        }
    }

    @Test
    fun `포인트 내역 유형 필터 조회`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 1, "적립1") }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("2000"), 2, "적립2") }
            query { pointService.usePointsInTransaction(user.id.value, BigDecimal("500"), 3, BigDecimal("5000")) }

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
    fun `잔액 조회 시 계정 없으면 실패`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()

            // When & Then - NotFoundException 발생
            assertFailsWith<NotFoundException> {
                pointService.getBalance(user.id.value)
            }
        }
    }

    // ===== 입력 검증 테스트 =====

    @Test
    fun `포인트 금액 검증 테스트 - 음수·최대값 초과`() {
        runBlocking {
            // Given
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }

            // When & Then - InvalidPointException 발생
            assertFailsWith<InvalidPointException> {
                query {
                    pointService.earnPointsInTransaction(
                        userId = user.id.value,
                        amount = BigDecimal("-100"),
                        orderId = 1,
                        description = "음수 적립"
                    )
                }
            }
            assertFailsWith<InvalidPointException> {
                query {
                    pointService.earnPointsInTransaction(
                        userId = user.id.value,
                        amount = BigDecimal("1000001"),  // 최대 1,000,000
                        orderId = 1,
                        description = "초과 적립"
                    )
                }
            }
        }
    }

    // ===== 포인트 만료 무한 루프·동시 사용 회귀 테스트 =====

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `만료 알림은 사용자별로 합산해 한 번 보낸다`() {
        runBlocking {
            clearMocks(notificationService, answers = false)
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 1, "적립1") }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 2, "적립2") }

            val past = Clock.System.now().minus(365.days).toLocalDateTime(TimeZone.UTC)
            query {
                PointHistoryDao.find {
                    (PointHistoryTable.userId eq user.id.value) and (PointHistoryTable.type eq PointType.EARN)
                }.forEach { it.expiresAt = past }
            }

            assertEquals(2, pointService.expirePoints())

            // 같은 금액 두 건이 분 단위 중복 제거로 한 건(1000)으로 합쳐지지 않게 합산값 1건
            coVerify(exactly = 1, timeout = 2000) {
                notificationService.sendPointExpiredNotification(userId = user.id.value, amount = any())
            }
            coVerify {
                notificationService.sendPointExpiredNotification(
                    userId = user.id.value,
                    amount = match { it.compareTo(BigDecimal("2000")) == 0 }
                )
            }
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `만료 처리 - 소진되어 잔액 부족한 적립분도 무한 루프 없이 만료일만 정리`() {
        runBlocking {
            // Given: 1000 적립 후 600 사용 -> 잔액 400 (만료 대상 1000보다 작음 = underwater)
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 1, "적립") }
            query { pointService.usePointsInTransaction(user.id.value, BigDecimal("600"), 2, BigDecimal("2000")) }

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
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 1, "적립") }

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

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `만료 처리 - 잔액을 읽은 뒤 끼어든 포인트 사용을 덮어쓰지 않는다`() {
        runBlocking {
            // Given: first 만료분이 먼저 처리되고, 그 차감이 반영되는 순간 target이 포인트를 쓰려 함
            val first = TestFixtures.createTestUser(email = "exp-a@example.com", username = "expa")
            val target = TestFixtures.createTestUser(email = "exp-b@example.com", username = "expb")
            query { pointService.createPointAccountInTransaction(first.id.value) }
            query { pointService.createPointAccountInTransaction(target.id.value) }
            query { pointService.earnPointsInTransaction(first.id.value, BigDecimal("1000"), 1, "적립") }
            query { pointService.earnPointsInTransaction(target.id.value, BigDecimal("1000"), 2, "만료될 적립") }
            query { pointService.earnPointsInTransaction(target.id.value, BigDecimal("1000"), 3, "남는 적립") }

            val now = Clock.System.now()
            query {
                PointHistoryDao.find { PointHistoryTable.orderId eq 1 }.single().expiresAt = now.minus(3.days).toLocalDateTime(TimeZone.UTC)
                PointHistoryDao.find { PointHistoryTable.orderId eq 2 }.single().expiresAt = now.minus(2.days).toLocalDateTime(TimeZone.UTC)
            }
            val firstPointId = query { PointDao.find { PointTable.userId eq first.id.value }.single().id.value }

            // first 차감이 반영된 직후 target 잔액 조회까지 끝난 시점에 사용 요청이 끼어듦
            val interleaved = AtomicBoolean(false)
            val hook = EntityHook.subscribe { change ->
                if (change.changeType == EntityChangeType.Updated && change.entityClass.table == PointTable &&
                    change.entityId.value == firstPointId && interleaved.compareAndSet(false, true)
                ) {
                    thread {
                        runCatching {
                            runBlocking {
                                query {
                                    TransactionManager.current().exec("SET LOCK_TIMEOUT 100")  // 잠겨 있으면 곧바로 포기
                                    pointService.usePointsInTransaction(target.id.value, BigDecimal("300"), 4, BigDecimal("1000"))
                                }
                            }
                        }
                    }.join()
                }
            }

            // When
            try { pointService.expirePoints() } finally { EntityHook.unsubscribe(hook) }

            // Then: 사용이 잠금에 막히든 반영되든 잔액과 원장이 일치
            assertTrue(interleaved.get())
            val (balance, ledger) = query {
                PointDao.find { PointTable.userId eq target.id.value }.single()
                    .let { it.balance to (it.totalEarned - it.totalUsed - it.totalExpired) }
            }
            assertEquals(0, ledger.compareTo(balance))
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `만료 처리가 겹쳐 같은 적립 내역을 두 번 읽어도 한 번만 차감한다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            query { pointService.createPointAccountInTransaction(user.id.value) }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 1, "만료될 적립") }
            query { pointService.earnPointsInTransaction(user.id.value, BigDecimal("1000"), 2, "남는 적립") }
            query {
                PointHistoryDao.find { PointHistoryTable.orderId eq 1 }.single().expiresAt =
                    Clock.System.now().minus(1.days).toLocalDateTime(TimeZone.UTC)
            }

            // 만료 대상 목록을 읽은 직후 다른 만료 처리가 끝까지 끼어듦
            val historyRepository = spyk(PointHistoryRepositoryImpl())
            val overlapping = PointService(
                pointRepository = PointRepositoryImpl(),
                pointHistoryRepository = historyRepository,
                notificationService = notificationService,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            )
            val interleaved = AtomicBoolean(false)
            coEvery { historyRepository.findExpiredPoints(any(), any()) } coAnswers {
                val batch = callOriginal()
                if (batch.isNotEmpty() && interleaved.compareAndSet(false, true)) {
                    thread { runBlocking { pointService.expirePoints() } }.join()
                }
                batch
            }

            overlapping.expirePoints()

            assertTrue(interleaved.get())
            val (balance, expireRows) = query {
                PointDao.find { PointTable.userId eq user.id.value }.single().balance to
                    PointHistoryDao.find { (PointHistoryTable.userId eq user.id.value) and (PointHistoryTable.type eq PointType.EXPIRE) }.count()
            }
            assertEquals(0, BigDecimal("1000").compareTo(balance), "잔액 $balance")
            assertEquals(1L, expireRows)
        }
    }
}
