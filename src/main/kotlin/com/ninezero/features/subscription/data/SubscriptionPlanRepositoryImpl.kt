package com.ninezero.features.subscription.data

import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.subscription.SubscriptionPlanDao
import com.ninezero.core.database.entities.subscription.SubscriptionPlanTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal

class SubscriptionPlanRepositoryImpl : SubscriptionPlanRepository {

    /** 크리에이터의 구독 플랜 생성 */
    override suspend fun createPlan(
        creatorId: Int,
        name: String,
        tier: SubscriptionPlanTier,
        description: String,
        price: BigDecimal,
        benefits: String
    ): SubscriptionPlanDao {
        return SubscriptionPlanDao.new {
            this.creatorId = creatorId
            this.name = name
            this.tier = tier
            this.description = description
            this.price = price
            this.benefits = benefits
        }
    }

    /** 플랜 부분 수정 */
    override suspend fun updatePlan(
        planId: Int,
        name: String?,
        description: String?,
        price: BigDecimal?,
        benefits: String?,
        isActive: Boolean?
    ): Boolean {
        val now = nowUtc()

        return SubscriptionPlanTable.update({ SubscriptionPlanTable.id eq planId }) {
            name?.let { value -> it[SubscriptionPlanTable.name] = value }  // null은 유지
            description?.let { value -> it[SubscriptionPlanTable.description] = value }
            price?.let { value -> it[SubscriptionPlanTable.price] = value }
            benefits?.let { value -> it[SubscriptionPlanTable.benefits] = value }
            isActive?.let { value -> it[SubscriptionPlanTable.isActive] = value }
            it[updatedAt] = now
        } > 0
    }

    /** 플랜 삭제 */
    override suspend fun deletePlan(planId: Int): Boolean {
        val now = nowUtc()

        return SubscriptionPlanTable.update({ SubscriptionPlanTable.id eq planId }) {
            it[isActive] = false
            it[updatedAt] = now
        } > 0
    }

    /** 크리에이터 해제용 플랜 비활성화 */
    override suspend fun deactivatePlansByCreator(creatorId: Int): Int {
        val now = nowUtc()

        return SubscriptionPlanTable.update({
            (SubscriptionPlanTable.creatorId eq creatorId) and
                    (SubscriptionPlanTable.isActive eq true)
        }) {
            it[isActive] = false
            it[updatedAt] = now
        }
    }

    /** 비활성 포함 플랜 조회 */
    override suspend fun findPlanById(planId: Int): SubscriptionPlanDao? {
        return SubscriptionPlanDao.findById(planId)
    }

    /** 비활성 포함 플랜 일괄 조회 */
    override suspend fun findPlansByIds(planIds: List<Int>): List<SubscriptionPlanDao> {
        if (planIds.isEmpty()) return emptyList()

        return SubscriptionPlanDao.find {
            SubscriptionPlanTable.id inList planIds
        }.toList()
    }

    /** 비활성 포함 플랜 목록 조회 */
    override suspend fun findPlansByCreator(
        creatorId: Int,
        page: Int,
        limit: Int
    ): List<SubscriptionPlanDao> {
        return SubscriptionPlanDao.find { SubscriptionPlanTable.creatorId eq creatorId }
            .orderBy(SubscriptionPlanTable.createdAt to SortOrder.DESC, SubscriptionPlanTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 크리에이터의 활성 플랜 목록 조회 */
    override suspend fun findActivePlansByCreator(
        creatorId: Int,
        page: Int,
        limit: Int
    ): List<SubscriptionPlanDao> {
        return SubscriptionPlanDao.find {
            (SubscriptionPlanTable.creatorId eq creatorId) and
                    (SubscriptionPlanTable.isActive eq true)
        }
            .orderBy(SubscriptionPlanTable.createdAt to SortOrder.DESC, SubscriptionPlanTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 크리에이터의 플랜 수 */
    override suspend fun countPlansByCreator(creatorId: Int): Int {
        return SubscriptionPlanDao.find { SubscriptionPlanTable.creatorId eq creatorId }.count().toInt()
    }

    /** 크리에이터의 활성 플랜 수 */
    override suspend fun countActivePlansByCreator(creatorId: Int): Int {
        return SubscriptionPlanDao.find {
            (SubscriptionPlanTable.creatorId eq creatorId) and
                    (SubscriptionPlanTable.isActive eq true)
        }.count().toInt()
    }
}
