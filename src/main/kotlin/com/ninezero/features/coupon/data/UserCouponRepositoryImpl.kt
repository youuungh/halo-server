package com.ninezero.features.coupon.data

import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.coupon.UserCouponDao
import com.ninezero.core.database.entities.coupon.UserCouponTable
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update

class UserCouponRepositoryImpl : UserCouponRepository {

    // 발급 중지된 쿠폰도 받은 쿠폰은 만료일까지 사용
    private fun availableCondition(userId: Int, now: LocalDateTime): Op<Boolean> = Op.build {
        (UserCouponTable.userId eq userId) and
                (UserCouponTable.status eq UserCouponStatus.AVAILABLE) and
                (UserCouponTable.expiresAt greaterEq now)
    }

    /** 사용자 쿠폰 발급 */
    override suspend fun create(
        userId: Int,
        couponId: Int,
        expiresAt: LocalDateTime
    ): UserCouponDao {
        return UserCouponDao.new {
            this.userId = userId
            this.couponId = couponId
            this.claimedAt = nowUtc()
            this.expiresAt = expiresAt
        }
    }

    /** 사용자 쿠폰 USED 처리 */
    override suspend fun markAsUsed(userCouponId: Int, orderId: Int): Boolean {
        val userCoupon = UserCouponDao.findById(userCouponId) ?: return false
        userCoupon.status = UserCouponStatus.USED
        userCoupon.usedAt = nowUtc()
        userCoupon.orderId = orderId
        return true
    }

    /** 사용자 쿠폰 useCount +1 */
    override suspend fun incrementUseCount(userCouponId: Int): Boolean {
        val userCoupon = UserCouponDao.findById(userCouponId) ?: return false
        userCoupon.useCount += 1
        return true
    }

    /** 환불 시 useCount -1 */
    override suspend fun refund(userCouponId: Int, maxUseCount: Int): Boolean {
        val userCoupon = UserCouponDao.findById(userCouponId) ?: return false

        if (userCoupon.useCount > 0) {
            userCoupon.useCount -= 1
        }

        if (userCoupon.useCount < maxUseCount && userCoupon.expiresAt >= nowUtc()) {
            userCoupon.status = UserCouponStatus.AVAILABLE  // 남은 횟수가 생기고 미만료면 복구
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
            it[updatedAt] = now
        }
    }

    /** 탈퇴 정리용 보유 쿠폰 삭제 */
    override suspend fun deleteAllByUser(userId: Int): Int {
        return UserCouponTable.deleteWhere { this.userId eq userId }  // 주문의 쿠폰 스냅샷은 보존
    }

    /** 사용자의 특정 쿠폰 조회 */
    override suspend fun findByUserIdAndCouponId(userId: Int, couponId: Int): UserCouponDao? {
        return UserCouponDao.find {
            (UserCouponTable.userId eq userId) and (UserCouponTable.couponId eq couponId)
        }.singleOrNull()
    }

    /** 잠금 걸린 사용자 쿠폰 조회 */
    override suspend fun findByUserIdAndCouponIdForUpdate(userId: Int, couponId: Int): UserCouponDao? {
        return UserCouponDao.find {
            (UserCouponTable.userId eq userId) and (UserCouponTable.couponId eq couponId)
        }.forUpdate().singleOrNull()?.also { it.refresh(flush = false) }  // 잠금 후 최신 사용 횟수 재조회
    }

    /** 사용자 쿠폰 지갑 목록 조회 */
    override suspend fun findByUserId(userId: Int, page: Int, limit: Int): List<UserCouponDao> {
        return UserCouponDao.find { UserCouponTable.userId eq userId }
            .orderBy(UserCouponTable.claimedAt to SortOrder.DESC, UserCouponTable.id to SortOrder.DESC)  // claimedAt desc
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 사용 가능한 쿠폰 목록 조회 */
    override suspend fun findAvailableByUserId(userId: Int, page: Int, limit: Int): List<UserCouponDao> {
        val now = nowUtc()

        return UserCouponDao.find(availableCondition(userId, now))
            .orderBy(UserCouponTable.expiresAt to SortOrder.ASC, UserCouponTable.id to SortOrder.DESC)  // expiresAt asc
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 사용자의 status별 쿠폰 목록 조회 */
    override suspend fun findByUserIdAndStatus(userId: Int, status: UserCouponStatus, page: Int, limit: Int): List<UserCouponDao> {
        return UserCouponDao.find {
            (UserCouponTable.userId eq userId) and (UserCouponTable.status eq status)  // 발행 쿠폰 상태 무관
        }
            .orderBy(UserCouponTable.claimedAt to SortOrder.DESC, UserCouponTable.id to SortOrder.DESC)  // claimedAt desc
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 사용자 쿠폰 수 */
    override suspend fun countByUserId(userId: Int): Long {
        return UserCouponDao.find { UserCouponTable.userId eq userId }.count()
    }

    /** 사용 가능한 쿠폰 수 */
    override suspend fun countAvailableByUserId(userId: Int): Long {
        val now = nowUtc()

        return UserCouponDao.find(availableCondition(userId, now)).count()
    }

    /** 사용자의 status별 쿠폰 수 */
    override suspend fun countByUserIdAndStatus(userId: Int, status: UserCouponStatus): Long {
        return UserCouponDao.find {
            (UserCouponTable.userId eq userId) and (UserCouponTable.status eq status)
        }.count()
    }
}
