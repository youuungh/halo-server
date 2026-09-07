package com.ninezero.service

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponStatus
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.features.coupon.data.CouponRepositoryImpl
import com.ninezero.features.coupon.data.UserCouponRepositoryImpl
import com.ninezero.features.coupon.domain.CouponService
import com.ninezero.features.coupon.presentation.models.request.CouponRequest
import com.ninezero.features.coupon.presentation.models.request.UpdateCouponRequest
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.minusDays
import com.ninezero.helper.TestFixtures.plusDays
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CouponService 테스트
 *
 * 테스트 케이스: 25개
 * - 쿠폰 생성: 5개
 * - 쿠폰 조회: 3개
 * - 쿠폰 수정: 2개
 * - 쿠폰 삭제: 2개
 * - 쿠폰 발급: 6개
 * - 내 쿠폰 조회: 2개
 * - 페이지네이션: 2개
 * - 검증: 3개
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponServiceTest {

    private lateinit var couponService: CouponService
    private lateinit var couponRepository: CouponRepositoryImpl
    private lateinit var userCouponRepository: UserCouponRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()
            couponRepository = CouponRepositoryImpl()
            userCouponRepository = UserCouponRepositoryImpl()
            couponService = CouponService(couponRepository, userCouponRepository)
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

    // ===== 쿠폰 생성 테스트 (5개) =====

    @Test
    fun `쿠폰 생성 성공 - 자동 코드 생성`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()
            val request = CouponRequest(
                code = null,
                name = "신규 회원 할인",
                description = "첫 구매 시 10% 할인",
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "10000",
                maxDiscountAmount = "5000",
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            val response = couponService.createCoupon(creator.id.value, request)

            assertNotNull(response)
            assertEquals("신규 회원 할인", response.name)
            assertEquals(CouponType.PERCENTAGE, response.type)
            assertTrue(response.code.matches(Regex("[A-Z0-9]{12}")), "코드 형식이 올바르지 않습니다")
        }
    }

    @Test
    fun `쿠폰 생성 성공 - 수동 코드 입력`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()
            val request = CouponRequest(
                code = "WELCOME10",
                name = "환영 쿠폰",
                description = "신규 가입 환영 쿠폰",
                type = CouponType.FIXED_AMOUNT,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "5000",
                minOrderAmount = "20000",
                maxDiscountAmount = null,
                totalQuantity = 50,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(7).toString()
            )

            val response = couponService.createCoupon(creator.id.value, request)

            assertNotNull(response)
            assertEquals("WELCOME10", response.code)
            assertEquals("환영 쿠폰", response.name)
            assertEquals(CouponType.FIXED_AMOUNT, response.type)
        }
    }

    @Test
    fun `쿠폰 생성 실패 - 중복 코드`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()
            val request1 = CouponRequest(
                code = "DUPLICATE",
                name = "첫 번째 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            couponService.createCoupon(creator.id.value, request1)
            val request2 = request1.copy(name = "두 번째 쿠폰")

            assertFailsWith<ConflictException> {
                couponService.createCoupon(creator.id.value, request2)
            }
        }
    }

    @Test
    fun `쿠폰 생성 실패 - 할인율 100퍼센트 초과`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()
            val request = CouponRequest(
                code = null,
                name = "초과 할인 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "150",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            assertFailsWith<InvalidInputException> {
                couponService.createCoupon(creator.id.value, request)
            }
        }
    }

    @Test
    fun `쿠폰 생성 실패 - 종료일이 시작일보다 이름`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()
            val request = CouponRequest(
                code = null,
                name = "잘못된 기간 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.plusDays(30).toString(),
                endDate = now.toString()
            )

            assertFailsWith<InvalidInputException> {
                couponService.createCoupon(creator.id.value, request)
            }
        }
    }

    // ===== 쿠폰 조회 테스트 (3개) =====

    @Test
    fun `쿠폰 목록 조회 - 크리에이터별`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()

            repeat(3) { i ->
                val request = CouponRequest(
                    code = "COUPON$i",
                    name = "쿠폰 ${i + 1}",
                    description = null,
                    type = CouponType.PERCENTAGE,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10",
                    minOrderAmount = "0",
                    maxDiscountAmount = null,
                    totalQuantity = 100,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = now.toString(),
                    endDate = now.plusDays(30).toString()
                )
                couponService.createCoupon(creator.id.value, request)
            }

            val response = couponService.getMyCoupons(creator.id.value, page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(3, response.totalCount)
            assertEquals(3, response.items.size)
        }
    }

    @Test
    fun `활성 쿠폰 목록 조회`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()

            repeat(2) { i ->
                val request = CouponRequest(
                    code = "ACTIVE$i",
                    name = "활성 쿠폰 ${i + 1}",
                    description = null,
                    type = CouponType.PERCENTAGE,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10",
                    minOrderAmount = "0",
                    maxDiscountAmount = null,
                    totalQuantity = 100,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = now.toString(),
                    endDate = now.plusDays(30).toString()
                )
                couponService.createCoupon(creator.id.value, request)
            }

            val futureRequest = CouponRequest(
                code = "FUTURE",
                name = "미래 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.plusDays(10).toString(),
                endDate = now.plusDays(40).toString()
            )
            couponService.createCoupon(creator.id.value, futureRequest)

            val response = couponService.getActiveCoupons(page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(2, response.totalCount, "활성 쿠폰은 2개여야 합니다")
        }
    }

    @Test
    fun `쿠폰 상세 조회 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()
            val createRequest = CouponRequest(
                code = "DETAIL",
                name = "상세 조회 쿠폰",
                description = "테스트용 쿠폰",
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "15",
                minOrderAmount = "10000",
                maxDiscountAmount = "5000",
                totalQuantity = 50,
                maxUseCount = 2,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            val createResponse = couponService.createCoupon(creator.id.value, createRequest)
            val couponId = createResponse.id
            val response = couponService.getCouponById(couponId)

            assertNotNull(response)
            assertEquals("DETAIL", response.code)
            assertEquals("상세 조회 쿠폰", response.name)
            assertEquals(BigDecimal(response.discountValue).compareTo(BigDecimal("15")), 0)
        }
    }

    // ===== 쿠폰 수정 테스트 (2개) =====

    @Test
    fun `쿠폰 수정 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()
            val createRequest = CouponRequest(
                code = "UPDATE",
                name = "수정 전 이름",
                description = "수정 전 설명",
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "10000",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            val createResponse = couponService.createCoupon(creator.id.value, createRequest)
            val couponId = createResponse.id

            val updateRequest = UpdateCouponRequest(
                name = "수정 후 이름",
                description = "수정 후 설명",
                minOrderAmount = "15000",
                maxDiscountAmount = "3000",
                totalQuantity = 150,
                maxUseCount = 2,
                status = CouponStatus.ACTIVE,
                startDate = null,
                endDate = null
            )

            val response = couponService.updateCoupon(couponId, creator.id.value, updateRequest)

            assertNotNull(response)
            assertEquals("수정 후 이름", response.name)
            assertEquals("수정 후 설명", response.description)
            assertEquals(150, response.totalQuantity)
        }
    }

    @Test
    fun `쿠폰 수정 실패 - 권한 없음`() {
        runBlocking {
            val creator1 = TestFixtures.createTestCreator(email = "creator1@test.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@test.com", username = "creator2")
            val now = nowUtc()

            val createRequest = CouponRequest(
                code = "NOPERM",
                name = "권한 테스트 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            val createResponse = couponService.createCoupon(creator1.id.value, createRequest)
            val couponId = createResponse.id

            val updateRequest = UpdateCouponRequest(
                name = "해킹 시도",
                description = null,
                minOrderAmount = null,
                maxDiscountAmount = null,
                totalQuantity = null,
                maxUseCount = null,
                status = null,
                startDate = null,
                endDate = null
            )

            assertFailsWith<ForbiddenException> {
                couponService.updateCoupon(couponId, creator2.id.value, updateRequest)
            }
        }
    }

    // ===== 쿠폰 삭제 테스트 (2개) =====

    @Test
    fun `쿠폰 삭제 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()
            val createRequest = CouponRequest(
                code = "DELETE",
                name = "삭제 테스트 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            val createResponse = couponService.createCoupon(creator.id.value, createRequest)
            val couponId = createResponse.id
            couponService.deleteCoupon(couponId, creator.id.value)

            val checkResponse = couponService.getCouponById(couponId)
            assertEquals(CouponStatus.DISABLED, checkResponse.status)
        }
    }

    @Test
    fun `쿠폰 삭제 실패 - 권한 없음`() {
        runBlocking {
            val creator1 = TestFixtures.createTestCreator(email = "creator1@test.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@test.com", username = "creator2")
            val now = nowUtc()

            val createRequest = CouponRequest(
                code = "DELPERM",
                name = "삭제 권한 테스트 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            val createResponse = couponService.createCoupon(creator1.id.value, createRequest)
            val couponId = createResponse.id

            assertFailsWith<ForbiddenException> {
                couponService.deleteCoupon(couponId, creator2.id.value)
            }
        }
    }

    // ===== 쿠폰 발급 테스트 (6개) =====

    @Test
    fun `쿠폰 발급 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser(email = "user@test.com", username = "user")
            val now = nowUtc()

            val createRequest = CouponRequest(
                code = "CLAIM01",
                name = "발급 테스트 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            couponService.createCoupon(creator.id.value, createRequest)
            val response = couponService.claimCoupon(user.id.value, "CLAIM01")

            assertNotNull(response)
            assertEquals("CLAIM01", response.coupon.code)
            assertEquals(UserCouponStatus.AVAILABLE, response.status)
        }
    }

    @Test
    fun `쿠폰 발급 실패 - 잘못된 코드`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            assertFailsWith<NotFoundException> {
                couponService.claimCoupon(user.id.value, "INVALID")
            }
        }
    }

    @Test
    fun `쿠폰 발급 실패 - 중복 발급`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = nowUtc()

            val createRequest = CouponRequest(
                code = "DUP01",
                name = "중복 발급 테스트",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            couponService.createCoupon(creator.id.value, createRequest)
            couponService.claimCoupon(user.id.value, "DUP01")

            assertFailsWith<ConflictException> {
                couponService.claimCoupon(user.id.value, "DUP01")
            }
        }
    }

    @Test
    fun `쿠폰 발급 실패 - 품절`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val users = TestFixtures.createTestUsers(3)
            val now = nowUtc()

            val createRequest = CouponRequest(
                code = "SOLDOUT",
                name = "품절 테스트 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 2,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            couponService.createCoupon(creator.id.value, createRequest)
            couponService.claimCoupon(users[0].id.value, "SOLDOUT")
            couponService.claimCoupon(users[1].id.value, "SOLDOUT")

            assertFailsWith<BadRequestException> {
                couponService.claimCoupon(users[2].id.value, "SOLDOUT")
            }
        }
    }

    @Test
    fun `쿠폰 발급 실패 - 기간 만료`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = nowUtc()

            val createRequest = CouponRequest(
                code = "EXPIRED",
                name = "만료된 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.minusDays(60).toString(),
                endDate = now.minusDays(30).toString()
            )

            couponService.createCoupon(creator.id.value, createRequest)

            assertFailsWith<BadRequestException> {
                couponService.claimCoupon(user.id.value, "EXPIRED")
            }
        }
    }

    @Test
    fun `쿠폰 발급 실패 - 비활성 쿠폰`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = nowUtc()

            val createRequest = CouponRequest(
                code = "DISABLED",
                name = "비활성 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            val createResponse = couponService.createCoupon(creator.id.value, createRequest)
            val couponId = createResponse.id
            couponService.deleteCoupon(couponId, creator.id.value)

            assertFailsWith<BadRequestException> {
                couponService.claimCoupon(user.id.value, "DISABLED")
            }
        }
    }

    // ===== 내 쿠폰 목록 조회 테스트 (2개) =====

    @Test
    fun `내 쿠폰 목록 조회`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = nowUtc()

            repeat(3) { i ->
                val createRequest = CouponRequest(
                    code = "MYCOUPON$i",
                    name = "내 쿠폰 ${i + 1}",
                    description = null,
                    type = CouponType.PERCENTAGE,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10",
                    minOrderAmount = "0",
                    maxDiscountAmount = null,
                    totalQuantity = 100,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = now.toString(),
                    endDate = now.plusDays(30).toString()
                )

                couponService.createCoupon(creator.id.value, createRequest)
                couponService.claimCoupon(user.id.value, "MYCOUPON$i")
            }

            val response = couponService.getMyCouponList(user.id.value, page = 1, limit = 10)

            assertNotNull(response)
            assertEquals(3, response.coupons.totalCount)
            assertEquals(3, response.availableCount)
        }
    }

    @Test
    fun `내 쿠폰 목록 조회 - 사용 가능한 쿠폰만`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = nowUtc()

            repeat(2) { i ->
                val createRequest = CouponRequest(
                    code = "VALID$i",
                    name = "유효한 쿠폰 ${i + 1}",
                    description = null,
                    type = CouponType.PERCENTAGE,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10",
                    minOrderAmount = "0",
                    maxDiscountAmount = null,
                    totalQuantity = 100,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = now.toString(),
                    endDate = now.plusDays(30).toString()
                )

                couponService.createCoupon(creator.id.value, createRequest)
                couponService.claimCoupon(user.id.value, "VALID$i")
            }

            val response = couponService.getMyCouponList(
                userId = user.id.value,
                page = 1,
                limit = 10,
                status = UserCouponStatus.AVAILABLE
            )

            assertNotNull(response)
            assertEquals(2, response.coupons.items.size)
            assertTrue(response.coupons.items.all { it.status == UserCouponStatus.AVAILABLE })
        }
    }

    @Test
    fun `내 쿠폰 목록 조회 - 발행 쿠폰이 비활성화되면 미사용 쿠폰은 지갑에서 제외`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = nowUtc()

            repeat(2) { i ->
                val createRequest = CouponRequest(
                    code = "DEADCOUPON$i",
                    name = "죽을 쿠폰 ${i + 1}",
                    description = null,
                    type = CouponType.PERCENTAGE,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10",
                    minOrderAmount = "0",
                    maxDiscountAmount = null,
                    totalQuantity = 100,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = now.toString(),
                    endDate = now.plusDays(30).toString()
                )
                couponService.createCoupon(creator.id.value, createRequest)
                couponService.claimCoupon(user.id.value, "DEADCOUPON$i")
            }

            // 하나는 사용 처리 — 사용완료 기록은 발행 쿠폰이 죽어도 유지돼야 한다
            query {
                val usedCoupon = couponRepository.findByCode("DEADCOUPON0")!!
                val userCoupon = userCouponRepository.findByUserIdAndCouponId(user.id.value, usedCoupon.id.value)!!
                userCouponRepository.markAsUsed(userCoupon.id.value, orderId = 1)
            }

            // 크리에이터 해제/탈퇴 teardown과 동일 경로로 발행 쿠폰 전체 비활성화
            query { couponRepository.disableActiveCouponsByCreator(creator.id.value) }

            // 사용가능 칩·카운트: 죽은 미사용 쿠폰 제외 → 0
            val available = couponService.getMyCouponList(
                userId = user.id.value, page = 1, limit = 10, status = UserCouponStatus.AVAILABLE
            )
            assertEquals(0, available.coupons.items.size)
            assertEquals(0, available.availableCount)

            // 전체 칩: 미사용 죽은 쿠폰은 제외, 사용완료 기록은 유지 → 1
            val all = couponService.getMyCouponList(userId = user.id.value, page = 1, limit = 10)
            assertEquals(1, all.coupons.totalCount)
            assertTrue(all.coupons.items.all { it.status == UserCouponStatus.USED })
        }
    }

    // ===== 페이지네이션 테스트 (2개) =====

    @Test
    fun `쿠폰 목록 페이지네이션 테스트`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()

            repeat(15) { i ->
                val request = CouponRequest(
                    code = "PAGE$i",
                    name = "페이지 쿠폰 ${i + 1}",
                    description = null,
                    type = CouponType.PERCENTAGE,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10",
                    minOrderAmount = "0",
                    maxDiscountAmount = null,
                    totalQuantity = 100,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = now.toString(),
                    endDate = now.plusDays(30).toString()
                )
                couponService.createCoupon(creator.id.value, request)
            }

            val page1Response = couponService.getMyCoupons(creator.id.value, page = 1, limit = 10)
            val page2Response = couponService.getMyCoupons(creator.id.value, page = 2, limit = 10)

            assertEquals(10, page1Response.items.size)
            assertEquals(15, page1Response.totalCount)

            assertEquals(5, page2Response.items.size)
            assertEquals(15, page2Response.totalCount)
        }
    }

    @Test
    fun `내 쿠폰 목록 페이지네이션 테스트`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = nowUtc()

            repeat(12) { i ->
                val request = CouponRequest(
                    code = "USERPAGE$i",
                    name = "사용자 페이지 쿠폰 ${i + 1}",
                    description = null,
                    type = CouponType.PERCENTAGE,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10",
                    minOrderAmount = "0",
                    maxDiscountAmount = null,
                    totalQuantity = 100,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = now.toString(),
                    endDate = now.plusDays(30).toString()
                )
                couponService.createCoupon(creator.id.value, request)
                couponService.claimCoupon(user.id.value, "USERPAGE$i")
            }

            val page1Response = couponService.getMyCouponList(user.id.value, page = 1, limit = 5)
            val page2Response = couponService.getMyCouponList(user.id.value, page = 2, limit = 5)
            val page3Response = couponService.getMyCouponList(user.id.value, page = 3, limit = 5)

            assertEquals(5, page1Response.coupons.items.size)
            assertEquals(5, page2Response.coupons.items.size)
            assertEquals(2, page3Response.coupons.items.size)
            assertEquals(12, page1Response.coupons.totalCount)
        }
    }

    // ===== 검증 테스트 (3개) =====

    @Test
    fun `쿠폰 코드 대소문자 무시 테스트`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = nowUtc()

            val createRequest = CouponRequest(
                code = "lowercase",
                name = "소문자 코드 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            val createResponse = couponService.createCoupon(creator.id.value, createRequest)
            val response = couponService.claimCoupon(user.id.value, "LOWERCASE")

            assertNotNull(response)
            assertEquals("LOWERCASE", createResponse.code)
        }
    }

    @Test
    fun `쿠폰 발급 수량 증가 확인`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val users = TestFixtures.createTestUsers(3)
            val now = nowUtc()

            val createRequest = CouponRequest(
                code = "QUANTITY",
                name = "수량 테스트 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 10,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            val createResponse = couponService.createCoupon(creator.id.value, createRequest)
            val couponId = createResponse.id

            users.forEach { user ->
                couponService.claimCoupon(user.id.value, "QUANTITY")
            }

            val checkResponse = couponService.getCouponById(couponId)
            assertEquals(3, checkResponse.issuedQuantity)
        }
    }

    @Test
    fun `무료 배송 쿠폰 생성 테스트`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = nowUtc()

            val request = CouponRequest(
                code = "FREESHIP",
                name = "무료 배송 쿠폰",
                description = "배송비 무료",
                type = CouponType.FREE_SHIPPING,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "0",
                minOrderAmount = "30000",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now.toString(),
                endDate = now.plusDays(30).toString()
            )

            val response = couponService.createCoupon(creator.id.value, request)

            assertNotNull(response)
            assertEquals(CouponType.FREE_SHIPPING, response.type)
            assertEquals("무료 배송 쿠폰", response.name)
        }
    }
}