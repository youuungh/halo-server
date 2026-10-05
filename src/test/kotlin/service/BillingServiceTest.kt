package com.ninezero.service

import com.ninezero.core.common.config.BillingKeyStatus
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.commerce.BillingKeyDao
import com.ninezero.core.database.entities.commerce.BillingKeyTable
import com.ninezero.core.database.entities.commerce.PaymentCustomerDao
import com.ninezero.core.database.entities.commerce.PaymentCustomerTable
import com.ninezero.core.payment.TossBillingIssueResponse
import com.ninezero.core.payment.TossBillingIssueResult
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.features.commerce.data.BillingKeyRepositoryImpl
import com.ninezero.features.commerce.data.PaymentCustomerRepositoryImpl
import com.ninezero.features.commerce.domain.BillingService
import com.ninezero.features.commerce.presentation.models.response.BillingKeyResponse
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.dao.EntityChangeType
import org.jetbrains.exposed.dao.EntityHook
import org.junit.jupiter.api.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillingServiceTest {

    private lateinit var billingService: BillingService
    private val tossPaymentClient: TossPaymentClient = mockk(relaxed = true)
    private val issued = AtomicInteger(0)

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()
            billingService = BillingService(PaymentCustomerRepositoryImpl(), BillingKeyRepositoryImpl(), tossPaymentClient)
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()
            issued.set(0)
            coEvery { tossPaymentClient.issueBillingKey(any(), any()) } answers {
                TossBillingIssueResult.Success(TossBillingIssueResponse(billingKey = "bk-${issued.incrementAndGet()}"))
            }
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    private suspend fun keysOf(userId: Int): List<Pair<String, BillingKeyStatus>> = query {
        BillingKeyDao.find { BillingKeyTable.userId eq userId }.map { it.billingKey to it.status }
    }

    @Test
    fun `카드를 다시 등록하면 이전 카드는 삭제 상태가 되고 활성 카드는 하나다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            billingService.issueBillingKey(user.id.value, "auth-1")
            billingService.issueBillingKey(user.id.value, "auth-2")

            val keys = keysOf(user.id.value)
            assertEquals(1, keys.count { it.second == BillingKeyStatus.ACTIVE })
            assertEquals(2, keys.size)
            assertEquals("bk-2", keys.single { it.second == BillingKeyStatus.ACTIVE }.first)
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `처음 카드 등록이 거의 동시에 두 번 들어와도 둘 다 성공하고 고객 키와 활성 카드는 하나다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            // 첫 요청의 고객 키 행이 저장된 직후 두 번째 요청이 끼어듦
            val interleaved = AtomicBoolean(false)
            var second: Result<BillingKeyResponse>? = null
            var secondThread: Thread? = null
            val hook = EntityHook.subscribe { change ->
                if (change.changeType == EntityChangeType.Created && change.entityClass.table == PaymentCustomerTable &&
                    interleaved.compareAndSet(false, true)
                ) {
                    secondThread = thread { second = runCatching { runBlocking { billingService.issueBillingKey(user.id.value, "auth-b") } } }
                    secondThread.join(1500)
                }
            }

            val first = try { runCatching { billingService.issueBillingKey(user.id.value, "auth-a") } } finally { EntityHook.unsubscribe(hook) }
            secondThread?.join()

            assertTrue(interleaved.get())
            assertTrue(first.isSuccess, "첫 등록: ${first.exceptionOrNull()}")
            assertTrue(second!!.isSuccess, "두 번째 등록: ${second.exceptionOrNull()}")
            assertEquals(1L, query { PaymentCustomerDao.find { PaymentCustomerTable.userId eq user.id.value }.count() })
            assertEquals(1, keysOf(user.id.value).count { it.second == BillingKeyStatus.ACTIVE })
        }
    }
}
