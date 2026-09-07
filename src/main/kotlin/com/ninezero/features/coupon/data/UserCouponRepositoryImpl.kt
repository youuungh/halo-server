package com.ninezero.features.coupon.data

import com.ninezero.core.common.config.CouponStatus
import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.coupon.CouponTable
import com.ninezero.core.database.entities.coupon.UserCouponDao
import com.ninezero.core.database.entities.coupon.UserCouponTable
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.update

class UserCouponRepositoryImpl : UserCouponRepository {

    /** 사용자 쿠폰 발급 */
    override suspend fun create(
        userId: Int,
        couponId: Int,
        expiresAt: LocalDateTime
    ): UserCouponDao {
        return UserCouponDao.new {
            this.userId = userId
            this.couponId = couponId
            this.status = UserCouponStatus.AVAILABLE
            this.useCount = 0
            this.claimedAt = nowUtc()
            this.expiresAt = expiresAt
        }
    }

    /** 사용자 쿠폰 USED 처리 */
    override suspend fun markAsUsed(userCouponId: Int, orderId: Int): Boolean {
        val userCoupon = UserCouponDao.findById(userCouponId) ?: return false
        val now = nowUtc()

        userCoupon.status = UserCouponStatus.USED
        userCoupon.usedAt = now
        userCoupon.orderId = orderId
        return true
    }

    /** 사용자 쿠폰 useCount +1 */
    override suspend fun incrementUseCount(userCouponId: Int): Boolean {
        val userCoupon = UserCouponDao.findById(userCouponId) ?: return false
        userCoupon.useCount += 1
        return true
    }

    /** 탈퇴 정리용 보유 쿠폰 삭제 */
    override suspend fun deleteAllByUser(userId: Int): Int {
        return UserCouponTable.deleteWhere { this.userId eq userId }  // 주문의 쿠폰 스냅샷은 보존
    }

    /** 환불 시 useCount -1 */
    override suspend fun refund(userCouponId: Int): Boolean {
        val userCoupon = UserCouponDao.findById(userCouponId) ?: return false
        val now = nowUtc()

        if (userCoupon.useCount > 0) {
            userCoupon.useCount -= 1
        }

        if (userCoupon.useCount == 0 && userCoupon.expiresAt >= now) {
            userCoupon.status = UserCouponStatus.AVAILABLE  // 0이 되고 미만료만 복구
            userCoupon.usedAt = null
            userCoupon.orderId = null
        }

        return true
    }

    /** 만료된 AVAILABLE 쿠폰 EXPIRED 처리 */
    override suspend fun expireUserCoupons(now: LocalDateTime): Int {
        return UserCouponTable.update({
            (UserCouponTable.status eq UserCouponStatus.AVAILABLE) and
                    (UserCouponTable.expiresAt less now)
        }) {
            it[status] = UserCouponStatus.EXPIRED
        }
    }

    /** 사용자 쿠폰 조회 */
    override suspend fun findById(userCouponId: Int): UserCouponDao? {
        return UserCouponDao.findById(userCouponId)
    }

    /** 사용자의 특정 쿠폰 조회 */
    override suspend fun findByUserIdAndCouponId(userId: Int, couponId: Int): UserCouponDao? {
        return UserCouponDao.find {
            (UserCouponTable.userId eq userId) and (UserCouponTable.couponId eq couponId)
        }.singleOrNull()
    }

    /** 사용자 쿠폰 지갑 목록 조회 */
    override suspend fun findByUserId(userId: Int, page: Int, limit: Int): List<UserCouponDao> {
        return UserCouponDao.wrapRows(
            UserCouponTable
                .innerJoin(CouponTable, { UserCouponTable.couponId }, { CouponTable.id })
                .select(UserCouponTable.columns)
                .where {
                    (UserCouponTable.userId eq userId) and
                            ((UserCouponTable.status neq UserCouponStatus.AVAILABLE) or  // 비활성 쿠폰의 미사용분만 제외
                                    (CouponTable.status eq CouponStatus.ACTIVE))
                }
                .orderBy(UserCouponTable.claimedAt to SortOrder.DESC, UserCouponTable.id to SortOrder.DESC)  // claimedAt desc
                .limit(limit).offset(page.toOffset(limit))
        ).toList()
    }

    /** 사용 가능한 쿠폰 목록 조회 */
    override suspend fun findAvailableByUserId(userId: Int, page: Int, limit: Int): List<UserCouponDao> {
        val now = nowUtc()

        return UserCouponDao.wrapRows(
            UserCouponTable
                .innerJoin(CouponTable, { UserCouponTable.couponId }, { CouponTable.id })
                .select(UserCouponTable.columns)
                .where {
                    (UserCouponTable.userId eq userId) and
                            (UserCouponTable.status eq UserCouponStatus.AVAILABLE) and
                            (UserCouponTable.expiresAt greaterEq now) and
                            (CouponTable.status eq CouponStatus.ACTIVE)
                }
                .orderBy(UserCouponTable.expiresAt to SortOrder.ASC, UserCouponTable.id to SortOrder.DESC)  // expiresAt asc
                .limit(limit).offset(page.toOffset(limit))
        ).toList()
    }

    /** 사용자의 status별 쿠폰 목록 조회 */
    override suspend fun findByUserIdAndStatus(
        userId: Int,
        status: UserCouponStatus,
        page: Int,
        limit: Int
    ): List<UserCouponDao> {
        return UserCouponDao.find {
            (UserCouponTable.userId eq userId) and (UserCouponTable.status eq status)  // 발행 쿠폰 상태 무관
        }
            .orderBy(UserCouponTable.claimedAt to SortOrder.DESC, UserCouponTable.id to SortOrder.DESC)  // claimedAt desc
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 사용자 쿠폰 수 */
    override suspend fun countByUserId(userId: Int): Long {
        return UserCouponTable
            .innerJoin(CouponTable, { UserCouponTable.couponId }, { CouponTable.id })
            .select(UserCouponTable.id)
            .where {
                (UserCouponTable.userId eq userId) and
                        ((UserCouponTable.status neq UserCouponStatus.AVAILABLE) or
                                (CouponTable.status eq CouponStatus.ACTIVE))
            }
            .count()  // 죽은 쿠폰 제외
    }

    /** 사용 가능한 쿠폰 수 */
    override suspend fun countAvailableByUserId(userId: Int): Long {
        val now = nowUtc()

        return UserCouponTable
            .innerJoin(CouponTable, { UserCouponTable.couponId }, { CouponTable.id })
            .select(UserCouponTable.id)
            .where {
                (UserCouponTable.userId eq userId) and
                        (UserCouponTable.status eq UserCouponStatus.AVAILABLE) and
                        (UserCouponTable.expiresAt greaterEq now) and
                        (CouponTable.status eq CouponStatus.ACTIVE)
            }
            .count()
    }

    /** 사용자의 status별 쿠폰 수 */
    override suspend fun countByUserIdAndStatus(userId: Int, status: UserCouponStatus): Long {
        return UserCouponDao.find {
            (UserCouponTable.userId eq userId) and (UserCouponTable.status eq status)
        }.count()
    }

    /** 사용자의 특정 쿠폰 발급 여부 */
    override suspend fun existsByUserIdAndCouponId(userId: Int, couponId: Int): Boolean {
        return UserCouponDao.find {
            (UserCouponTable.userId eq userId) and (UserCouponTable.couponId eq couponId)
        }.count() > 0
    }
}
