package com.ninezero.service

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.common.util.query
import com.ninezero.features.coupon.calcCouponDiscount
import com.ninezero.features.coupon.data.CouponRepository
import com.ninezero.features.coupon.data.CouponRepositoryImpl
import com.ninezero.features.coupon.data.UserCouponRepositoryImpl
import com.ninezero.features.coupon.domain.CouponRedemptionService
import com.ninezero.features.coupon.domain.CouponService
import com.ninezero.features.coupon.domain.CouponValidationResult
import com.ninezero.features.coupon.presentation.models.request.CouponRequest
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.minusDays
import com.ninezero.helper.TestFixtures.plusDays
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponRedemptionServiceTest {

    private lateinit var couponService: CouponService
    private lateinit var redemptionService: CouponRedemptionService
    private lateinit var couponRepository: CouponRepositoryImpl
    private lateinit var userCouponRepository: UserCouponRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()
            couponRepository = CouponRepositoryImpl()
            userCouponRepository = UserCouponRepositoryImpl()
            couponService = CouponService(couponRepository, userCouponRepository, UserRepositoryImpl())
            redemptionService = CouponRedemptionService(
                couponRepository = couponRepository,
                userCouponRepository = userCouponRepository,
                subscriptionRepository = SubscriptionRepositoryImpl(),
                subscriptionPlanRepository = SubscriptionPlanRepositoryImpl()
            )
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking { TestDatabase.clearAll() }
    }

    @AfterAll
    fun tearDown() {
        runBlocking { TestDatabase.cleanup() }
    }

    @Test
    fun `동시 쿠폰 사용 - 최대 사용 횟수 초과 방지`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            issuePercentageCoupon(code = "CONCUR", creatorId = creator.id.value, userId = user.id.value)

            val results = listOf(
                async(Dispatchers.IO) {
                    runCatching { query { redemptionService.useCouponInTransaction(user.id.value, "CONCUR", 1) } }
                },
                async(Dispatchers.IO) {
                    runCatching { query { redemptionService.useCouponInTransaction(user.id.value, "CONCUR", 2) } }
                }
            ).awaitAll()

            val successes = results.count { it.isSuccess }
            val useCount = query {
                val coupon = couponRepository.findByCode("CONCUR")!!
                userCouponRepository.findByUserIdAndCouponId(user.id.value, coupon.id.value)!!.useCount
            }
            assertEquals(1, successes)
            assertEquals(1, useCount)
        }
    }

    @Test
    fun `다회권 쿠폰 - 남은 횟수가 있으면 사용 뒤에도 AVAILABLE, 마지막 사용에 USED`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            issuePercentageCoupon(code = "TWICE", creatorId = creator.id.value, userId = user.id.value, maxUseCount = 2)

            query { redemptionService.useCouponInTransaction(user.id.value, "TWICE", 1) }
            assertEquals(1 to UserCouponStatus.AVAILABLE, userCouponState(user.id.value, "TWICE"))

            query { redemptionService.useCouponInTransaction(user.id.value, "TWICE", 2) }
            assertEquals(2 to UserCouponStatus.USED, userCouponState(user.id.value, "TWICE"))
        }
    }

    @Test
    fun `쿠폰 복구 - 다 쓴 쿠폰을 한 번 돌려주면 남은 횟수만큼 다시 AVAILABLE`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            issuePercentageCoupon(code = "ONCE", creatorId = creator.id.value, userId = user.id.value)
            issuePercentageCoupon(code = "TWICE", creatorId = creator.id.value, userId = user.id.value, maxUseCount = 2)
            query { redemptionService.useCouponInTransaction(user.id.value, "ONCE", 1) }
            repeat(2) { query { redemptionService.useCouponInTransaction(user.id.value, "TWICE", it + 2) } }

            query { redemptionService.refundCouponInTransaction(user.id.value, "ONCE") }
            query { redemptionService.refundCouponInTransaction(user.id.value, "TWICE") }

            assertEquals(0 to UserCouponStatus.AVAILABLE, userCouponState(user.id.value, "ONCE"))
            assertEquals(1 to UserCouponStatus.AVAILABLE, userCouponState(user.id.value, "TWICE"))
        }
    }

    @Test
    fun `쿠폰 검증 중 저장소 오류는 422 문구로 바꾸지 않고 그대로 전파한다`() {
        runBlocking {
            val brokenRepository = mockk<CouponRepository>()
            coEvery { brokenRepository.findByCode(any()) } throws IllegalStateException("JDBC connection refused")
            val service = CouponRedemptionService(
                couponRepository = brokenRepository,
                userCouponRepository = userCouponRepository,
                subscriptionRepository = SubscriptionRepositoryImpl(),
                subscriptionPlanRepository = SubscriptionPlanRepositoryImpl()
            )

            assertFailsWith<IllegalStateException> {
                service.calcDiscountInTransaction(
                    userId = 1,
                    couponCode = "ANY",
                    orderAmount = BigDecimal("10000"),
                    productIds = listOf(1)
                )
            }
        }
    }

    /** 쿠폰 생성 후 발급 */
    private suspend fun issuePercentageCoupon(
        code: String,
        creatorId: Int,
        userId: Int,
        discountValue: String = "10",
        minOrderAmount: String = "0",
        maxDiscountAmount: String? = null,
        maxUseCount: Int = 1
    ) {
        val now = TestFixtures.nowTruncatedToSeconds()
        couponService.createCoupon(
            creatorId,
            CouponRequest(
                code = code,
                name = "검증 테스트 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = discountValue,
                minOrderAmount = minOrderAmount,
                maxDiscountAmount = maxDiscountAmount,
                totalQuantity = 100,
                maxUseCount = maxUseCount,
                targetIds = null,
                startDate = now.minusDays(1),  // 시작일이 정확히 now면 경계에 걸림
                endDate = now.plusDays(30)
            )
        )
        couponService.claimCoupon(userId, code)
    }

    private suspend fun userCouponState(userId: Int, code: String) = query {
        val coupon = couponRepository.findByCode(code)!!
        userCouponRepository.findByUserIdAndCouponId(userId, coupon.id.value)!!.let { it.useCount to it.status }
    }

    @Test
    fun `calcDiscount - 존재하지 않는 코드는 Invalid`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val result = query {
                redemptionService.calcDiscountInTransaction(
                    userId = user.id.value,
                    couponCode = "NOPE",
                    orderAmount = BigDecimal("10000"),
                    productIds = listOf(1)
                )
            }

            assertTrue(result is CouponValidationResult.Invalid)
        }
    }

    @Test
    fun `calcDiscount - 발급받지 않은 쿠폰은 Invalid`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser(email = "u@test.com", username = "u")
            val product = TestFixtures.createTestProduct(creator.id.value)

            // 쿠폰은 만들되 발급은 하지 않는다
            val now = TestFixtures.nowTruncatedToSeconds()
            couponService.createCoupon(
                creator.id.value,
                CouponRequest(
                    code = "UNCLAIMED", name = "미발급", description = null,
                    type = CouponType.PERCENTAGE, discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10", minOrderAmount = "0", maxDiscountAmount = null,
                    totalQuantity = 100, maxUseCount = 1, targetIds = null,
                    startDate = now, endDate = now.plusDays(30)
                )
            )

            val result = query {
                redemptionService.calcDiscountInTransaction(
                    userId = user.id.value,
                    couponCode = "UNCLAIMED",
                    orderAmount = BigDecimal("10000"),
                    productIds = listOf(product.id.value),
                    productCreatorMap = mapOf(product.id.value to creator.id.value)
                )
            }

            assertTrue(result is CouponValidationResult.Invalid)
        }
    }

    @Test
    fun `calcDiscount - 정상 쿠폰은 Valid이며 할인액을 담는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser(email = "u2@test.com", username = "u2")
            val product = TestFixtures.createTestProduct(creator.id.value)

            issuePercentageCoupon("PCT10", creator.id.value, user.id.value, discountValue = "10")

            val result = query {
                redemptionService.calcDiscountInTransaction(
                    userId = user.id.value,
                    couponCode = "PCT10",
                    orderAmount = BigDecimal("10000"),
                    productIds = listOf(product.id.value),
                    productCreatorMap = mapOf(product.id.value to creator.id.value),
                    creatorAmountMap = mapOf(creator.id.value to BigDecimal("10000"))
                )
            }

            assertTrue(result is CouponValidationResult.Valid)
            // 10% of 10000
            assertEquals(BigDecimal("1000"), result.discount)
        }
    }

    @Test
    fun `calcDiscount - 최소 주문 금액 미달은 Invalid`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser(email = "u3@test.com", username = "u3")
            val product = TestFixtures.createTestProduct(creator.id.value)

            issuePercentageCoupon("MIN5000", creator.id.value, user.id.value, minOrderAmount = "5000")

            val result = query {
                redemptionService.calcDiscountInTransaction(
                    userId = user.id.value,
                    couponCode = "MIN5000",
                    orderAmount = BigDecimal("3000"),
                    productIds = listOf(product.id.value),
                    productCreatorMap = mapOf(product.id.value to creator.id.value),
                    creatorAmountMap = mapOf(creator.id.value to BigDecimal("3000"))
                )
            }

            assertTrue(result is CouponValidationResult.Invalid)
            assertTrue(result.errorMessage.endsWith("(최소 5000원)"), result.errorMessage)
        }
    }

    @Test
    fun `쿠폰 복구 - 탈퇴로 보유 쿠폰이 지워졌으면 예외 없이 건너뜀`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser(email = "gone@test.com", username = "gone")
            issuePercentageCoupon("GONE10", creator.id.value, user.id.value)
            query { redemptionService.useCouponInTransaction(user.id.value, "GONE10", 1) }
            query { userCouponRepository.deleteAllByUser(user.id.value) }

            // 그룹 취소·미결제 자동취소가 여기서 멈추면 안 됨
            query { redemptionService.refundCouponInTransaction(user.id.value, "GONE10") }
        }
    }

    @Test
    fun `할인 계산 - 음수 최대 할인액이 저장돼 있어도 할인액은 0 아래로 내려가지 않음`() {
        val discount = calcCouponDiscount(
            orderAmount = BigDecimal("10000"),
            couponType = CouponType.FIXED_AMOUNT,
            discountValue = BigDecimal("5000"),
            maxDiscountAmount = BigDecimal("-30000")
        )

        assertEquals(0, discount.compareTo(BigDecimal.ZERO))
    }
}
