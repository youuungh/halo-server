package com.ninezero.features.subscription.data

import com.ninezero.core.common.config.SubscriptionStatus
import com.ninezero.core.common.exception.SubscriptionNotFoundException
import com.ninezero.core.common.util.ilike
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.subscription.SubscriptionDao
import com.ninezero.core.database.entities.subscription.SubscriptionPlanTable
import com.ninezero.core.database.entities.subscription.SubscriptionTable
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.database.entities.user.UserTable
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus

class SubscriptionRepositoryImpl : SubscriptionRepository {

    private fun activeCondition(now: LocalDateTime = nowUtc()) =
        (SubscriptionTable.status eq SubscriptionStatus.ACTIVE) and (SubscriptionTable.expiresAt greater now)

    /** 구독 생성 */
    override suspend fun createSubscription(
        userId: Int,
        creatorId: Int,
        planId: Int,
        startedAt: LocalDateTime,
        expiresAt: LocalDateTime,
        autoRenew: Boolean
    ): SubscriptionDao {
        val subscription = SubscriptionDao.new {
            this.userId = userId
            this.creatorId = creatorId
            this.planId = planId
            this.status = SubscriptionStatus.ACTIVE
            this.startedAt = startedAt
            this.expiresAt = expiresAt
            this.autoRenew = autoRenew
        }

        SubscriptionPlanTable.update({ SubscriptionPlanTable.id eq planId }) {
            it[subscriberCount] = subscriberCount.plus(1)
        }

        return subscription
    }

    /** 결제 실패 롤백용 구독 삭제 */
    override suspend fun deleteSubscription(subscriptionId: Int) {
        val subscription = SubscriptionDao.findById(subscriptionId) ?: return
        val planId = subscription.planId
        SubscriptionPlanTable.update({ SubscriptionPlanTable.id eq planId }) {
            it[subscriberCount] = subscriberCount.minus(1)  // createSubscription 카운트 롤백
        }
        subscription.delete()
    }

    /** 구독 취소 */
    override suspend fun cancelSubscription(subscriptionId: Int): Pair<SubscriptionDao, Int> {
        val now = nowUtc()

        val subscription = SubscriptionDao.findById(subscriptionId)
            ?: throw SubscriptionNotFoundException(subscriptionId)  // 없으면 예외

        val planId = subscription.planId

        subscription.status = SubscriptionStatus.CANCELLED
        subscription.cancelledAt = now

        SubscriptionPlanTable.update({ SubscriptionPlanTable.id eq planId }) {
            it[subscriberCount] = subscriberCount.minus(1)  // 구독 취소로 감소
        }

        return Pair(subscription, planId)
    }

    /** 구독 자동갱신 설정 */
    override suspend fun updateAutoRenew(subscriptionId: Int, autoRenew: Boolean): Boolean {
        return SubscriptionTable.update({ SubscriptionTable.id eq subscriptionId }) {
            it[SubscriptionTable.autoRenew] = autoRenew
        } > 0
    }

    /** 구독의 planId 교체 */
    override suspend fun updateSubscriptionPlan(subscriptionId: Int, planId: Int): Boolean {
        return SubscriptionTable.update({ SubscriptionTable.id eq subscriptionId }) {
            it[SubscriptionTable.planId] = planId  // subscriberCount 미보정
        } > 0
    }

    /** status 무관 구독 조회 */
    override suspend fun findSubscriptionById(subscriptionId: Int): SubscriptionDao? {
        return SubscriptionDao.find { SubscriptionTable.id eq subscriptionId }.firstOrNull()
    }

    /** 유저의 구독 목록 조회 */
    override suspend fun findUserSubscriptions(
        userId: Int,
        status: SubscriptionStatus?,
        page: Int,
        limit: Int
    ): List<SubscriptionDao> {
        val query = (SubscriptionTable.userId eq userId)

        val finalQuery = when (status) {
            SubscriptionStatus.ACTIVE -> query and activeCondition()  // ACTIVE는 만료 전만
            null -> query
            else -> query and (SubscriptionTable.status eq status)
        }

        return SubscriptionDao.find { finalQuery }
            .orderBy(SubscriptionTable.createdAt to SortOrder.DESC, SubscriptionTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 유저의 특정 크리에이터 유효 구독 조회 */
    override suspend fun findActiveSubscription(userId: Int, creatorId: Int): SubscriptionDao? {
        return SubscriptionDao.find {
            (SubscriptionTable.userId eq userId) and
                    (SubscriptionTable.creatorId eq creatorId) and
                    activeCondition()
        }.firstOrNull()
    }

    /** 여러 크리에이터 유효 구독 일괄 조회 */
    override suspend fun findActiveSubscriptionsByCreators(
        userId: Int,
        creatorIds: List<Int>
    ): List<SubscriptionDao> {
        if (creatorIds.isEmpty()) return emptyList()

        return SubscriptionDao.find {
            (SubscriptionTable.userId eq userId) and
                    (SubscriptionTable.creatorId inList creatorIds) and
                    activeCondition()
        }.toList()
    }

    /** 유효 구독 존재 여부 */
    override suspend fun checkSubscriptionAccess(
        userId: Int,
        creatorId: Int,
        planId: Int?
    ): Boolean {
        val query = (SubscriptionTable.userId eq userId) and
                (SubscriptionTable.creatorId eq creatorId) and
                activeCondition()

        val finalQuery = if (planId != null) {
            query and (SubscriptionTable.planId eq planId)  // planId 있으면 해당 플랜만
        } else {
            query
        }

        return SubscriptionDao.find { finalQuery }.count() > 0
    }

    /** 크리에이터의 유효 구독 목록 조회 */
    override suspend fun findCreatorSubscribers(
        creatorId: Int,
        page: Int,
        limit: Int
    ): List<SubscriptionDao> {
        return SubscriptionDao.find {
            (SubscriptionTable.creatorId eq creatorId) and
                    activeCondition()
        }
            .orderBy(SubscriptionTable.createdAt to SortOrder.DESC, SubscriptionTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 플랜의 유효 구독 목록 조회 */
    override suspend fun findPlanSubscribers(
        planId: Int,
        page: Int,
        limit: Int
    ): List<SubscriptionDao> {
        return SubscriptionDao.find {
            (SubscriptionTable.planId eq planId) and
                    activeCondition()
        }
            .orderBy(SubscriptionTable.createdAt to SortOrder.DESC, SubscriptionTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 구독자 검색 */
    override suspend fun findCreatorSubscribersWithSearch(
        creatorId: Int,
        search: String,
        page: Int,
        limit: Int
    ): List<Int> {
        val searchPattern = "%$search%"

        return SubscriptionTable
            .innerJoin(UserTable, { SubscriptionTable.userId }, { UserTable.id })
            .leftJoin(UserProfileTable, { UserTable.id }, { UserProfileTable.userId })
            .select(SubscriptionTable.userId)
            .where {
                (SubscriptionTable.creatorId eq creatorId) and
                        activeCondition() and
                        (UserTable.isActive eq true) and
                        ((UserTable.username ilike searchPattern) or (UserProfileTable.displayName ilike searchPattern))
            }
            .orderBy(SubscriptionTable.createdAt to SortOrder.DESC, SubscriptionTable.id to SortOrder.DESC)  // 최신 구독순
            .limit(limit).offset(page.toOffset(limit))
            .map { it[SubscriptionTable.userId] }
    }

    /** 구독자 검색 결과 수 */
    override suspend fun countCreatorSubscribersWithSearch(creatorId: Int, search: String): Int {
        val searchPattern = "%$search%"

        return SubscriptionTable
            .innerJoin(UserTable, { SubscriptionTable.userId }, { UserTable.id })
            .leftJoin(UserProfileTable, { UserTable.id }, { UserProfileTable.userId })
            .select(SubscriptionTable.userId)
            .where {
                (SubscriptionTable.creatorId eq creatorId) and
                        activeCondition() and
                        (UserTable.isActive eq true) and
                        ((UserTable.username ilike searchPattern) or (UserProfileTable.displayName ilike searchPattern))
            }
            .count().toInt()
    }

    /** 유저의 구독 수 */
    override suspend fun countUserSubscriptions(
        userId: Int,
        status: SubscriptionStatus?
    ): Int {
        val query = (SubscriptionTable.userId eq userId)

        val finalQuery = when (status) {
            SubscriptionStatus.ACTIVE -> query and activeCondition()
            null -> query
            else -> query and (SubscriptionTable.status eq status)
        }

        return SubscriptionDao.find { finalQuery }.count().toInt()
    }

    /** 크리에이터의 유효 구독 수 */
    override suspend fun countCreatorSubscribers(creatorId: Int): Int {
        return SubscriptionDao.find {
            (SubscriptionTable.creatorId eq creatorId) and
                    activeCondition()
        }.count().toInt()
    }

    /** 플랜의 유효 구독 수 */
    override suspend fun countPlanSubscribers(planId: Int): Int {
        return SubscriptionDao.find {
            (SubscriptionTable.planId eq planId) and
                    activeCondition()
        }.count().toInt()
    }

    /** 만료 처리 대상 조회 */
    override suspend fun findExpiredSubscriptions(): List<SubscriptionDao> {
        val now = nowUtc()

        return SubscriptionDao.find {
            (SubscriptionTable.status eq SubscriptionStatus.ACTIVE) and
                    (SubscriptionTable.expiresAt less now)  // autoRenew 무관
        }.toList()
    }

    /** 구독 EXPIRED 전환 */
    override suspend fun expireSubscription(subscriptionId: Int): Boolean {
        val now = nowUtc()

        return SubscriptionTable.update({ SubscriptionTable.id eq subscriptionId }) {
            it[status] = SubscriptionStatus.EXPIRED  // subscriberCount 미변경
            it[updatedAt] = now
        } > 0
    }

    /** 탈퇴 정리용 구독 즉시 만료 */
    override suspend fun expireAllActiveByUser(userId: Int): Int {
        val now = nowUtc()

        return SubscriptionTable.update({
            (SubscriptionTable.userId eq userId) and
                    (SubscriptionTable.status eq SubscriptionStatus.ACTIVE)
        }) {
            it[status] = SubscriptionStatus.EXPIRED  // subscriberCount 미변경
            it[updatedAt] = now
        }
    }

    /** 갱신 대상 조회 */
    override suspend fun findSubscriptionsForRenewal(): List<SubscriptionDao> {
        val now = nowUtc()
        val tomorrow = now.date.plus(DatePeriod(days = 1))
            .atTime(now.hour, now.minute, now.second, now.nanosecond)

        return SubscriptionDao.find {
            (SubscriptionTable.status eq SubscriptionStatus.ACTIVE) and
                    (SubscriptionTable.autoRenew eq true) and
                    (SubscriptionTable.expiresAt less tomorrow) and
                    (SubscriptionTable.expiresAt greaterEq now)  // 지난 구독 제외
        }.toList()
    }

    /** 구독 만료일 연장 */
    override suspend fun extendSubscription(
        subscriptionId: Int,
        newExpiresAt: LocalDateTime
    ): Boolean {
        val now = nowUtc()

        return SubscriptionTable.update({ SubscriptionTable.id eq subscriptionId }) {
            it[expiresAt] = newExpiresAt
            it[updatedAt] = now
        } > 0
    }
}
