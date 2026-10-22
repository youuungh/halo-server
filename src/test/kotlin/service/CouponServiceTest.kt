package com.ninezero.service

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponStatus
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.features.coupon.data.CouponRepositoryImpl
import com.ninezero.features.coupon.data.UserCouponRepositoryImpl
import com.ninezero.features.coupon.domain.CouponService
import com.ninezero.features.coupon.presentation.models.request.CouponRequest
import com.ninezero.features.coupon.presentation.models.request.UpdateCouponRequest
import com.ninezero.features.user.data.UserRepositoryImpl
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import com.ninezero.helper.TestFixtures.minusDays
import com.ninezero.helper.TestFixtures.plusDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            couponService = CouponService(couponRepository, userCouponRepository, UserRepositoryImpl())
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

    // ===== 쿠폰 생성 테스트 =====

    @Test
    fun `쿠폰 생성 실패 - 크리에이터가 아니면 거부`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val now = TestFixtures.nowTruncatedToSeconds()
            val request = CouponRequest(
                code = null,
                name = "권한 없는 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = null,
                maxDiscountAmount = null,
                totalQuantity = 10,
                maxUseCount = 1,
                targetIds = null,
                startDate = now,
                endDate = now.plusDays(30)
            )

            assertFailsWith<ForbiddenException> {
                couponService.createCoupon(user.id.value, request)
            }
            assertEquals(0L, query { couponRepository.countByCreatorId(user.id.value) })
        }
    }

    @Test
    fun `쿠폰 생성 실패 - 중복 코드`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = TestFixtures.nowTruncatedToSeconds()
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
                startDate = now,
                endDate = now.plusDays(30)
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
            val now = TestFixtures.nowTruncatedToSeconds()
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
                startDate = now,
                endDate = now.plusDays(30)
            )

            assertFailsWith<InvalidInputException> {
                couponService.createCoupon(creator.id.value, request)
            }
        }
    }

    @Test
    fun `쿠폰 생성 실패 - 최대 할인 금액 0원 이하`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = TestFixtures.nowTruncatedToSeconds()
            val request = CouponRequest(
                code = null,
                name = "음수 상한 쿠폰",
                description = null,
                type = CouponType.FIXED_AMOUNT,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "5000",
                minOrderAmount = "0",
                maxDiscountAmount = "-30000",
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now,
                endDate = now.plusDays(30)
            )

            assertFailsWith<InvalidInputException> {
                couponService.createCoupon(creator.id.value, request)
            }
            assertFailsWith<InvalidInputException> {
                couponService.createCoupon(creator.id.value, request.copy(maxDiscountAmount = "0"))
            }
        }
    }

    @Test
    fun `쿠폰 생성 실패 - 빈 수동 코드`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = TestFixtures.nowTruncatedToSeconds()
            val request = CouponRequest(
                code = "",
                name = "빈 코드 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now,
                endDate = now.plusDays(30)
            )

            val error = assertFailsWith<InvalidCouponCodeException> {
                couponService.createCoupon(creator.id.value, request)
            }

            assertEquals(Errors.Coupon.COUPON_CODE_FORMAT_INVALID, error.message)
        }
    }

    // ===== 쿠폰 조회 테스트 =====

    @Test
    fun `쿠폰 목록 조회 - 크리에이터별`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val otherCreator = TestFixtures.createTestCreator(email = "other@test.com", username = "other")
            val now = TestFixtures.nowTruncatedToSeconds()

            fun request(code: String, name: String) = CouponRequest(
                code = code,
                name = name,
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = 100,
                maxUseCount = 1,
                targetIds = null,
                startDate = now,
                endDate = now.plusDays(30)
            )

            repeat(3) { i ->
                couponService.createCoupon(creator.id.value, request("COUPON$i", "쿠폰 ${i + 1}"))
            }
            // 다른 크리에이터 쿠폰은 목록 범위 밖
            couponService.createCoupon(otherCreator.id.value, request("OTHER0", "다른 크리에이터 쿠폰"))

            val response = couponService.getMyCoupons(creator.id.value, page = 1, limit = 10)

            assertEquals(3, response.totalCount)
            assertEquals(setOf("COUPON0", "COUPON1", "COUPON2"), response.items.map { it.code }.toSet())
        }
    }

    // ===== 쿠폰 수정 테스트 =====

    @Test
    fun `쿠폰 수정 성공`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = TestFixtures.nowTruncatedToSeconds()
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
                startDate = now,
                endDate = now.plusDays(30)
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

            assertEquals("수정 후 이름", response.name)
            assertEquals("수정 후 설명", response.description)
            assertEquals(150, response.totalQuantity)
        }
    }

    @Test
    fun `쿠폰 수정 - 설명·최소 주문금액·최대 할인액은 빈 값이면 지움`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val couponId = createUpdatableCoupon(creator.id.value, "CLEAR")
            couponService.updateCoupon(
                couponId,
                creator.id.value,
                UpdateCouponRequest(description = "설명", minOrderAmount = "20000", maxDiscountAmount = "5000")
            )

            val cleared = couponService.updateCoupon(
                couponId,
                creator.id.value,
                UpdateCouponRequest(description = "", minOrderAmount = "", maxDiscountAmount = "")
            )

            assertNull(cleared.description)
            assertEquals(0, BigDecimal(cleared.minOrderAmount).compareTo(BigDecimal.ZERO))
            assertNull(cleared.maxDiscountAmount)

            val kept = couponService.updateCoupon(couponId, creator.id.value, UpdateCouponRequest(name = "이름만"))
            assertNull(kept.description)
            assertNull(kept.maxDiscountAmount)
        }
    }

    @Test
    fun `쿠폰 수정 실패 - 권한 없음`() {
        runBlocking {
            val creator1 = TestFixtures.createTestCreator(email = "creator1@test.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@test.com", username = "creator2")
            val now = TestFixtures.nowTruncatedToSeconds()

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
                startDate = now,
                endDate = now.plusDays(30)
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

    /** 수정 테스트용 기본 쿠폰, 시작 now·종료 30일 뒤 */
    private suspend fun createUpdatableCoupon(creatorId: Int, code: String, totalQuantity: Int = 100): Int {
        val now = TestFixtures.nowTruncatedToSeconds()
        return couponService.createCoupon(
            creatorId,
            CouponRequest(
                code = code,
                name = "수정 대상 쿠폰",
                description = null,
                type = CouponType.PERCENTAGE,
                discountTarget = CouponDiscountTarget.ALL,
                discountValue = "10",
                minOrderAmount = "0",
                maxDiscountAmount = null,
                totalQuantity = totalQuantity,
                maxUseCount = 1,
                targetIds = null,
                startDate = now,
                endDate = now.plusDays(30)
            )
        ).id
    }

    @Test
    fun `쿠폰 수정 실패 - 이미 발급된 수량보다 적은 총수량`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val users = TestFixtures.createTestUsers(count = 2)
            val couponId = createUpdatableCoupon(creator.id.value, "SHRINK")
            users.forEach { couponService.claimCoupon(it.id.value, "SHRINK") }

            assertFailsWith<InvalidInputException> {
                couponService.updateCoupon(couponId, creator.id.value, UpdateCouponRequest(totalQuantity = 1))
            }
        }
    }

    @Test
    fun `쿠폰 수정 실패 - 종료일만 바꿔 시작일보다 앞`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val couponId = createUpdatableCoupon(creator.id.value, "REVERSE")

            val error = assertFailsWith<InvalidInputException> {
                couponService.updateCoupon(
                    couponId,
                    creator.id.value,
                    UpdateCouponRequest(endDate = nowUtc().minusDays(1))
                )
            }
            assertEquals("종료일은 시작일보다 이후여야 합니다.", error.message)
        }
    }

    @Test
    fun `쿠폰 수정 실패 - 크리에이터 해제 뒤 재활성 시도`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val couponId = createUpdatableCoupon(creator.id.value, "REVIVE")
            query { UserDao.findById(creator.id.value)!!.role = UserRole.USER }

            assertFailsWith<ForbiddenException> {
                couponService.updateCoupon(couponId, creator.id.value, UpdateCouponRequest(status = CouponStatus.ACTIVE))
            }
        }
    }

    // ===== 쿠폰 삭제 테스트 =====

    @Test
    fun `쿠폰 삭제 실패 - 권한 없음`() {
        runBlocking {
            val creator1 = TestFixtures.createTestCreator(email = "creator1@test.com", username = "creator1")
            val creator2 = TestFixtures.createTestCreator(email = "creator2@test.com", username = "creator2")
            val now = TestFixtures.nowTruncatedToSeconds()

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
                startDate = now,
                endDate = now.plusDays(30)
            )

            val createResponse = couponService.createCoupon(creator1.id.value, createRequest)
            val couponId = createResponse.id

            assertFailsWith<ForbiddenException> {
                couponService.deleteCoupon(couponId, creator2.id.value)
            }
        }
    }

    // ===== 쿠폰 발급 테스트 =====

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
            val now = TestFixtures.nowTruncatedToSeconds()

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
                startDate = now,
                endDate = now.plusDays(30)
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
            val now = TestFixtures.nowTruncatedToSeconds()

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
                startDate = now,
                endDate = now.plusDays(30)
            )

            couponService.createCoupon(creator.id.value, createRequest)
            couponService.claimCoupon(users[0].id.value, "SOLDOUT")
            couponService.claimCoupon(users[1].id.value, "SOLDOUT")

            assertFailsWith<InvalidCouponCodeException> {
                couponService.claimCoupon(users[2].id.value, "SOLDOUT")
            }
            assertEquals(2, query { couponRepository.findByCode("SOLDOUT")!!.issuedQuantity })
        }
    }

    @Test
    fun `쿠폰 발급 실패 - 기간 만료`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = TestFixtures.nowTruncatedToSeconds()

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
                startDate = now.minusDays(60),
                endDate = now.minusDays(30)
            )

            couponService.createCoupon(creator.id.value, createRequest)

            assertFailsWith<InvalidCouponCodeException> {
                couponService.claimCoupon(user.id.value, "EXPIRED")
            }
        }
    }

    @Test
    fun `쿠폰 발급 실패 - 비활성 쿠폰`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = TestFixtures.nowTruncatedToSeconds()

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
                startDate = now,
                endDate = now.plusDays(30)
            )

            val createResponse = couponService.createCoupon(creator.id.value, createRequest)
            val couponId = createResponse.id
            couponService.deleteCoupon(couponId, creator.id.value)
            assertEquals(CouponStatus.DISABLED, query { couponRepository.findById(couponId)!!.status })

            assertFailsWith<InvalidCouponCodeException> {
                couponService.claimCoupon(user.id.value, "DISABLED")
            }
        }
    }

    @Test
    fun `쿠폰 삭제 - 관리 목록에서 빠지고 수정·재활성화 불가`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val now = TestFixtures.nowTruncatedToSeconds()
            val created = couponService.createCoupon(
                creator.id.value,
                CouponRequest(
                    code = "DELETED1",
                    name = "삭제 쿠폰",
                    description = null,
                    type = CouponType.PERCENTAGE,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10",
                    minOrderAmount = "0",
                    maxDiscountAmount = null,
                    totalQuantity = 100,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = now,
                    endDate = now.plusDays(30)
                )
            )

            couponService.deleteCoupon(created.id, creator.id.value)

            val list = couponService.getMyCoupons(creator.id.value, page = 1, limit = 20)
            assertTrue(list.items.none { it.id == created.id })
            assertEquals(0, list.totalCount)
            assertFailsWith<NotFoundException> {
                couponService.updateCoupon(created.id, creator.id.value, UpdateCouponRequest(status = CouponStatus.ACTIVE))
            }
        }
    }

    @Test
    fun `동시 쿠폰 발급 - 한정 수량 초과 방지`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val userA = TestFixtures.createTestUser(email = "claimA@example.com", username = "claimA")
            val userB = TestFixtures.createTestUser(email = "claimB@example.com", username = "claimB")
            val now = TestFixtures.nowTruncatedToSeconds()
            couponService.createCoupon(
                creator.id.value,
                CouponRequest(
                    code = "LAST1",
                    name = "한정 쿠폰",
                    description = null,
                    type = CouponType.PERCENTAGE,
                    discountTarget = CouponDiscountTarget.ALL,
                    discountValue = "10",
                    minOrderAmount = "0",
                    maxDiscountAmount = null,
                    totalQuantity = 1,
                    maxUseCount = 1,
                    targetIds = null,
                    startDate = now.minusDays(1),
                    endDate = now.plusDays(30)
                )
            )

            val results = listOf(
                async(Dispatchers.IO) { runCatching { couponService.claimCoupon(userA.id.value, "LAST1") } },
                async(Dispatchers.IO) { runCatching { couponService.claimCoupon(userB.id.value, "LAST1") } }
            ).awaitAll()

            val issued = query { couponRepository.findByCode("LAST1")!!.issuedQuantity }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, issued)
        }
    }

    // ===== 내 쿠폰 목록 조회 테스트 =====

    @Test
    fun `내 쿠폰 목록 조회 - 사용 가능한 쿠폰만`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = TestFixtures.nowTruncatedToSeconds()

            repeat(3) { i ->
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
                    startDate = now,
                    endDate = now.plusDays(30)
                )

                couponService.createCoupon(creator.id.value, createRequest)
                couponService.claimCoupon(user.id.value, "VALID$i")
            }

            // 하나는 사용 처리해 필터에서 빠져야 함
            query {
                val usedCoupon = couponRepository.findByCode("VALID2")!!
                val userCoupon = userCouponRepository.findByUserIdAndCouponId(user.id.value, usedCoupon.id.value)!!
                userCouponRepository.markAsUsed(userCoupon.id.value, orderId = 1)
            }

            val response = couponService.getMyCouponList(
                userId = user.id.value,
                page = 1,
                limit = 10,
                status = UserCouponStatus.AVAILABLE
            )

            assertEquals(setOf("VALID0", "VALID1"), response.coupons.items.map { it.coupon.code }.toSet())
            assertTrue(response.coupons.items.all { it.status == UserCouponStatus.AVAILABLE })
        }
    }

    @Test
    fun `내 쿠폰 목록 조회 - 발행 쿠폰이 비활성화돼도 받은 쿠폰은 지갑에 남는다`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = TestFixtures.nowTruncatedToSeconds()

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
                    startDate = now,
                    endDate = now.plusDays(30)
                )
                couponService.createCoupon(creator.id.value, createRequest)
                couponService.claimCoupon(user.id.value, "DEADCOUPON$i")
            }

            // 하나는 사용 처리
            query {
                val usedCoupon = couponRepository.findByCode("DEADCOUPON0")!!
                val userCoupon = userCouponRepository.findByUserIdAndCouponId(user.id.value, usedCoupon.id.value)!!
                userCouponRepository.markAsUsed(userCoupon.id.value, orderId = 1)
            }

            // 비활성 = 발급 중지, 크리에이터 해제/탈퇴 teardown과 같은 경로
            query { couponRepository.disableActiveCouponsByCreator(creator.id.value) }

            // 사용가능 칩·카운트: 받은 미사용 쿠폰은 그대로 → 1
            val available = couponService.getMyCouponList(
                userId = user.id.value, page = 1, limit = 10, status = UserCouponStatus.AVAILABLE
            )
            assertEquals(listOf("DEADCOUPON1"), available.coupons.items.map { it.coupon.code })
            assertEquals(1, available.availableCount)

            // 전체 칩: 미사용·사용완료 모두 유지 → 2
            val all = couponService.getMyCouponList(userId = user.id.value, page = 1, limit = 10)
            assertEquals(2, all.coupons.totalCount)
        }
    }

    // ===== 검증 테스트 =====

    @Test
    fun `쿠폰 코드 대소문자 무시`() {
        runBlocking {
            val creator = TestFixtures.createTestCreator()
            val user = TestFixtures.createTestUser()
            val now = TestFixtures.nowTruncatedToSeconds()

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
                startDate = now,
                endDate = now.plusDays(30)
            )

            val createResponse = couponService.createCoupon(creator.id.value, createRequest)
            val response = couponService.claimCoupon(user.id.value, "LOWERCASE")

            assertEquals("LOWERCASE", createResponse.code)
            assertEquals("LOWERCASE", response.coupon.code)
            assertEquals(UserCouponStatus.AVAILABLE, response.status)
        }
    }
}
