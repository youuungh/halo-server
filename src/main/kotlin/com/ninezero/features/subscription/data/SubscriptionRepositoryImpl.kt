package com.ninezero.features.subscription.data

import com.ninezero.core.common.config.SubscriptionStatus
import com.ninezero.core.common.util.containsLikePattern
import com.ninezero.core.common.util.ilike
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.subscription.SubscriptionDao
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

class SubscriptionRepositoryImpl : SubscriptionRepository {

    private fun activeCondition(now: LocalDateTime) =
        (SubscriptionTable.status eq SubscriptionStatus.ACTIVE) and (SubscriptionTable.expiresAt greater now)

    private fun userSubscriptionCondition(
        userId: Int,
        status: SubscriptionStatus?,
        now: LocalDateTime
    ): Op<Boolean> {
        val base = SubscriptionTable.userId eq userId

        return when (status) {
            SubscriptionStatus.ACTIVE -> base and activeCondition(now)  // ACTIVE는 만료 전만
            null -> base
            else -> base and (SubscriptionTable.status eq status)
        }
    }

    private fun subscriberSearchCondition(
        creatorId: Int,
        searchPattern: LikePattern,
        now: LocalDateTime
    ): Op<Boolean> =
        (SubscriptionTable.creatorId eq creatorId) and
                activeCondition(now) and
                ((UserTable.username ilike searchPattern) or (UserProfileTable.displayName ilike searchPattern))

    /** 구독 생성 */
    override suspend fun createSubscription(
        userId: Int,
        creatorId: Int,
        planId: Int,
        startedAt: LocalDateTime,
        expiresAt: LocalDateTime,
        autoRenew: Boolean
    ): SubscriptionDao {
        return SubscriptionDao.new {
            this.userId = userId
            this.creatorId = creatorId
            this.planId = planId
            this.status = SubscriptionStatus.ACTIVE
            this.startedAt = startedAt
            this.expiresAt = expiresAt
            this.autoRenew = autoRenew
        }
    }

    /** 결제 실패 롤백용 구독 삭제 */
    override suspend fun deleteSubscription(subscriptionId: Int) {
        val subscription = SubscriptionDao.findById(subscriptionId) ?: return
        subscription.delete()
    }

    /** 구독 자동갱신 설정 */
    override suspend fun updateAutoRenew(subscriptionId: Int, autoRenew: Boolean): Boolean {
        return SubscriptionTable.update({ SubscriptionTable.id eq subscriptionId }) {
            it[SubscriptionTable.autoRenew] = autoRenew
            it[updatedAt] = nowUtc()
        } > 0
    }

    /** 구독의 planId 교체 */
    override suspend fun updateSubscriptionPlan(subscriptionId: Int, planId: Int): Boolean {
        return SubscriptionTable.update({ SubscriptionTable.id eq subscriptionId }) {
            it[SubscriptionTable.planId] = planId
            it[updatedAt] = nowUtc()
        } > 0
    }

    /** status 무관 구독 조회 */
    override suspend fun findSubscriptionById(subscriptionId: Int): SubscriptionDao? {
        return SubscriptionDao.findById(subscriptionId)
    }

    /** 유저의 구독 목록 조회 */
    override suspend fun findUserSubscriptions(
        userId: Int,
        status: SubscriptionStatus?,
        page: Int,
        limit: Int
    ): List<SubscriptionDao> {
        val condition = userSubscriptionCondition(userId, status, nowUtc())

        return SubscriptionDao.find { condition }
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
                    activeCondition(nowUtc())
        }.firstOrNull()
    }

    /** 여러 크리에이터 유효 구독 일괄 조회 */
    override suspend fun findActiveSubscriptionsByCreators(
        userId: Int,
        creatorIds: Collection<Int>
    ): List<SubscriptionDao> {
        if (creatorIds.isEmpty()) return emptyList()

        return SubscriptionDao.find {
            (SubscriptionTable.userId eq userId) and
                    (SubscriptionTable.creatorId inList creatorIds) and
                    activeCondition(nowUtc())
        }.toList()
    }

    /** 여러 구독자·크리에이터 유효 구독 일괄 조회 */
    override suspend fun findActiveSubscriptionsByCreators(
        userIds: Collection<Int>,
        creatorIds: Collection<Int>
    ): List<SubscriptionDao> {
        if (userIds.isEmpty() || creatorIds.isEmpty()) return emptyList()

        return SubscriptionDao.find {
            (SubscriptionTable.userId inList userIds) and
                    (SubscriptionTable.creatorId inList creatorIds) and
                    activeCondition(nowUtc())
        }.toList()
    }

    /** 크리에이터의 유효 구독 목록 조회 */
    override suspend fun findCreatorSubscribers(
        creatorId: Int,
        page: Int,
        limit: Int
    ): List<SubscriptionDao> {
        return SubscriptionDao.find {
            (SubscriptionTable.creatorId eq creatorId) and
                    activeCondition(nowUtc())
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
        val searchPattern = containsLikePattern(search)

        return SubscriptionTable
            .innerJoin(UserTable, { SubscriptionTable.userId }, { UserTable.id })
            .leftJoin(UserProfileTable, { UserTable.id }, { UserProfileTable.userId })
            .select(SubscriptionTable.userId)
            .where(subscriberSearchCondition(creatorId, searchPattern, nowUtc()))
            .orderBy(SubscriptionTable.createdAt to SortOrder.DESC, SubscriptionTable.id to SortOrder.DESC)  // 최신 구독순
            .limit(limit).offset(page.toOffset(limit))
            .map { it[SubscriptionTable.userId] }
    }

    /** 구독자 검색 결과 수 */
    override suspend fun countCreatorSubscribersWithSearch(creatorId: Int, search: String): Int {
        val searchPattern = containsLikePattern(search)

        return SubscriptionTable
            .innerJoin(UserTable, { SubscriptionTable.userId }, { UserTable.id })
            .leftJoin(UserProfileTable, { UserTable.id }, { UserProfileTable.userId })
            .select(SubscriptionTable.userId)
            .where(subscriberSearchCondition(creatorId, searchPattern, nowUtc()))
            .count().toInt()
    }

    /** 유저의 구독 수 */
    override suspend fun countUserSubscriptions(
        userId: Int,
        status: SubscriptionStatus?
    ): Int {
        val condition = userSubscriptionCondition(userId, status, nowUtc())

        return SubscriptionDao.find { condition }.count().toInt()
    }

    /** 크리에이터의 유효 구독 수 */
    override suspend fun countCreatorSubscribers(creatorId: Int): Int {
        return SubscriptionDao.find {
            (SubscriptionTable.creatorId eq creatorId) and
                    activeCondition(nowUtc())
        }.count().toInt()
    }

    /** 플랜의 유효 구독 수 */
    override suspend fun countPlanSubscribers(planId: Int): Int {
        return SubscriptionDao.find {
            (SubscriptionTable.planId eq planId) and
                    activeCondition(nowUtc())
        }.count().toInt()
    }

    /** 플랜별 유효 구독 수 일괄 조회 */
    override suspend fun countPlanSubscribers(planIds: Collection<Int>): Map<Int, Int> {
        if (planIds.isEmpty()) return emptyMap()

        return SubscriptionTable
            .select(SubscriptionTable.planId, SubscriptionTable.id.count())
            .where { (SubscriptionTable.planId inList planIds) and activeCondition(nowUtc()) }
            .groupBy(SubscriptionTable.planId)
            .associate { it[SubscriptionTable.planId] to it[SubscriptionTable.id.count()].toInt() }  // 0건 플랜은 키 없음
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
            it[status] = SubscriptionStatus.EXPIRED
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
            it[status] = SubscriptionStatus.EXPIRED
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
