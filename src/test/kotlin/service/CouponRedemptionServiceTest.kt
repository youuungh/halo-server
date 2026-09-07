package com.ninezero.service

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.util.nowUtc
import com.ninezero.features.coupon.data.CouponRepositoryImpl
import com.ninezero.features.coupon.data.UserCouponRepositoryImpl
import com.ninezero.features.coupon.domain.CouponService
import com.ninezero.features.coupon.domain.CouponValidationResult
import com.ninezero.features.coupon.domain.CouponRedemptionService
import com.ninezero.features.coupon.presentation.models.request.CouponRequest
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.plusDays
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CouponRedemptionService.calcDiscount 테스트
 *
 * Triple<Boolean, String?, BigDecimal> → CouponValidationResult(sealed) 전환의 동작 보존 검증.
 * 이 경로는 기존에 단위 테스트가 전혀 없었다(주문 테스트는 전부 couponCodes=null).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponRedemptionServiceTest {

    private lateinit var couponService: CouponService
    private lateinit var validationService: CouponRedemptionService
    private lateinit var couponRepository: CouponRepositoryImpl
    private lateinit var userCouponRepository: UserCouponRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()
            couponRepository = CouponRepositoryImpl()
            userCouponRepository = UserCouponRepositoryImpl()
            couponService = CouponService(couponRepository, userCouponRepository)
            validationService = CouponRedemptionService(
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

    /** creatorId scope 판정을 통과하도록 productCreatorMap을 함께 넘겨 발급된 쿠폰을 계산한다. */
    private suspend fun issuePercentageCoupon(
        code: String,
        creatorId: Int,
        userId: Int,
        discountValue: String = "10",
        minOrderAmount: String = "0",
        maxDiscountAmount: String? = null
    ) {
        val now = nowUtc()
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
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )
        )
        couponService.claimCoupon(userId, code)
    }

    @Test
    fun `calcDiscount - 존재하지 않는 코드는 Invalid`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            val result = validationService.calcDiscount(
                userId = user.id.value,
                couponCode = "NOPE",
                orderAmount = BigDecimal("10000"),
                productIds = listOf(1)
            )

            assertTrue(result is CouponValidationResult.Invalid)
        }
    }

    @Test
    fun `calcDiscount - 발급받지 않은 쿠폰은 Invalid`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser(email = "u@test.com", username = "u")
            val product = TestFixtures.createTestProduct(creator.id.value)

            // 쿠폰은 만들되 발급(claim)은 하지 않는다
            val now = nowUtc()
            couponService.createCoupon(
                creator.id.value,
                CouponRequest(
                    code = "UNCLAIMED", name = "미발급", description = null,
                    type = CouponType.PERCENTAGE, discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10", minOrderAmount = "0", maxDiscountAmount = null,
                    totalQuantity = 100, maxUseCount = 1, targetIds = null,
                    startDate = now.toString(), endDate = now.plusDays(30).toString()
                )
            )

            val result = validationService.calcDiscount(
                userId = user.id.value,
                couponCode = "UNCLAIMED",
                orderAmount = BigDecimal("10000"),
                productIds = listOf(product.id.value),
                productCreatorMap = mapOf(product.id.value to creator.id.value)
            )

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

            val result = validationService.calcDiscount(
                userId = user.id.value,
                couponCode = "PCT10",
                orderAmount = BigDecimal("10000"),
                productIds = listOf(product.id.value),
                productCreatorMap = mapOf(product.id.value to creator.id.value),
                creatorAmountMap = mapOf(creator.id.value to BigDecimal("10000"))
            )

            assertTrue(result is CouponValidationResult.Valid)
            // 10% of 10000
            assertEquals(BigDecimal("1000"), (result as CouponValidationResult.Valid).discount)
        }
    }

    @Test
    fun `calcDiscount - 최소 주문 금액 미달은 Invalid`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser(email = "u3@test.com", username = "u3")
            val product = TestFixtures.createTestProduct(creator.id.value)

            issuePercentageCoupon("MIN5000", creator.id.value, user.id.value, minOrderAmount = "5000")

            val result = validationService.calcDiscount(
                userId = user.id.value,
                couponCode = "MIN5000",
                orderAmount = BigDecimal("3000"),
                productIds = listOf(product.id.value),
                productCreatorMap = mapOf(product.id.value to creator.id.value),
                creatorAmountMap = mapOf(creator.id.value to BigDecimal("3000"))
            )

            assertTrue(result is CouponValidationResult.Invalid)
        }
    }
}
