package com.ninezero.features.coupon.data

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponStatus
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.coupon.CouponDao
import com.ninezero.core.database.entities.coupon.CouponTable
import com.ninezero.core.database.entities.coupon.UserCouponDao
import com.ninezero.core.database.entities.coupon.UserCouponTable
import io.ktor.server.plugins.BadRequestException
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal

class CouponRepositoryImpl : CouponRepository {

    /** 쿠폰 생성 */
    override suspend fun create(
        creatorId: Int,
        code: String,
        name: String,
        description: String?,
        type: CouponType,
        discountTarget: CouponDiscountTarget,
        discountValue: BigDecimal,
        minOrderAmount: BigDecimal,
        maxDiscountAmount: BigDecimal?,
        totalQuantity: Int,
        maxUseCount: Int,
        targetIds: String?,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): CouponDao {
        return CouponDao.new {
            this.creatorId = creatorId
            this.code = code
            this.name = name
            this.description = description
            this.type = type
            this.discountTarget = discountTarget
            this.discountValue = discountValue
            this.minOrderAmount = minOrderAmount
            this.maxDiscountAmount = maxDiscountAmount
            this.totalQuantity = totalQuantity
            this.issuedQuantity = 0
            this.maxUseCount = maxUseCount
            this.targetIds = targetIds
            this.status = CouponStatus.ACTIVE
            this.startDate = startDate
            this.endDate = endDate
        }
    }

    /** 쿠폰 부분 수정 */
    override suspend fun updateCoupon(
        couponId: Int,
        name: String?,
        description: String?,
        minOrderAmount: BigDecimal?,
        maxDiscountAmount: BigDecimal?,
        totalQuantity: Int?,
        maxUseCount: Int?,
        status: CouponStatus?,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?
    ): Boolean {
        val coupon = CouponDao.findById(couponId) ?: return false  // 할인 조건 필드는 수정 불가

        name?.let { coupon.name = it }  // null은 유지
        description?.let { coupon.description = it }
        minOrderAmount?.let { coupon.minOrderAmount = it }
        maxDiscountAmount?.let { coupon.maxDiscountAmount = it }
        totalQuantity?.let { coupon.totalQuantity = it }
        maxUseCount?.let { coupon.maxUseCount = it }
        status?.let { coupon.status = it }
        startDate?.let { coupon.startDate = it }
        endDate?.let { coupon.endDate = it }

        return true
    }

    /** 쿠폰 삭제 */
    override suspend fun deleteCoupon(couponId: Int): Boolean {
        val coupon = CouponDao.findById(couponId) ?: return false
        coupon.status = CouponStatus.DISABLED
        return true
    }

    /** 크리에이터 해제용 쿠폰 비활성화 */
    override suspend fun disableActiveCouponsByCreator(creatorId: Int): Int {
        return CouponTable.update({
            (CouponTable.creatorId eq creatorId) and (CouponTable.status eq CouponStatus.ACTIVE)
        }) {
            it[status] = CouponStatus.DISABLED
            it[updatedAt] = nowUtc()
        }
    }

    /** 쿠폰 조회 */
    override suspend fun findById(couponId: Int): CouponDao? {
        return CouponDao.findById(couponId)
    }

    /** 쿠폰 일괄 조회 */
    override suspend fun findByIds(couponIds: List<Int>): List<CouponDao> {
        if (couponIds.isEmpty()) return emptyList()
        return CouponDao.find { CouponTable.id inList couponIds }.toList()
    }

    /** 코드로 쿠폰 조회 */
    override suspend fun findByCode(code: String): CouponDao? {
        return CouponDao.find { CouponTable.code eq code }.singleOrNull()
    }

    /** 상태 무관 크리에이터 쿠폰 목록 */
    override suspend fun findByCreatorId(creatorId: Int, page: Int, limit: Int): List<CouponDao> {
        return CouponDao.find { CouponTable.creatorId eq creatorId }
            .orderBy(CouponTable.createdAt to SortOrder.DESC, CouponTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 활성 + 기간 내 쿠폰 목록 */
    override suspend fun findActive(page: Int, limit: Int): List<CouponDao> {
        val now = nowUtc()

        return CouponDao.find {
            (CouponTable.status eq CouponStatus.ACTIVE) and
                    (CouponTable.startDate lessEq now) and
                    (CouponTable.endDate greaterEq now)
        }
            .orderBy(CouponTable.createdAt to SortOrder.DESC, CouponTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 코드로 쿠폰 발급 */
    override suspend fun claimByCode(userId: Int, code: String): Pair<CouponDao, Int> {
        val now = nowUtc()

        val coupon = CouponDao.find { CouponTable.code eq code }.singleOrNull()
            ?: throw NotFoundException(Errors.Coupon.COUPON_CODE_INVALID)

        if (coupon.status != CouponStatus.ACTIVE) {
            throw BadRequestException(Errors.Coupon.COUPON_DISABLED)
        }

        if (now < coupon.startDate || now > coupon.endDate) {
            throw BadRequestException(Errors.Coupon.COUPON_EXPIRED)
        }

        if (coupon.issuedQuantity >= coupon.totalQuantity) {
            throw BadRequestException(Errors.Coupon.COUPON_OUT_OF_STOCK)
        }

        val existingUserCoupon = UserCouponDao.find {
            (UserCouponTable.userId eq userId) and (UserCouponTable.couponId eq coupon.id.value)
        }.singleOrNull()

        if (existingUserCoupon != null) {
            throw ConflictException(Errors.Coupon.COUPON_ALREADY_CLAIMED)
        }

        val userCoupon = UserCouponDao.new {
            this.userId = userId
            this.couponId = coupon.id.value
            this.status = UserCouponStatus.AVAILABLE
            this.useCount = 0
            this.claimedAt = now
            this.expiresAt = coupon.endDate  // 만료는 쿠폰 endDate 그대로
        }

        coupon.issuedQuantity += 1

        return coupon to userCoupon.id.value
    }

    /** 크리에이터의 쿠폰 수 */
    override suspend fun countByCreatorId(creatorId: Int): Long {
        return CouponDao.find { CouponTable.creatorId eq creatorId }.count()
    }

    /** 쿠폰 수 */
    override suspend fun countActive(): Long {
        val now = nowUtc()

        return CouponDao.find {
            (CouponTable.status eq CouponStatus.ACTIVE) and
                    (CouponTable.startDate lessEq now) and
                    (CouponTable.endDate greaterEq now)
        }.count()
    }

    /** 쿠폰 코드 중복 여부 */
    override suspend fun existsByCode(code: String): Boolean {
        return CouponDao.find { CouponTable.code eq code }.count() > 0
    }
}
