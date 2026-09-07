package com.ninezero.features.subscription.data

import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.database.entities.subscription.SubscriptionPlanDao
import java.math.BigDecimal

interface SubscriptionPlanRepository {

    // 플랜 생성/수정/삭제
    suspend fun createPlan(
        creatorId: Int,
        name: String,
        tier: SubscriptionPlanTier,
        description: String,
        price: BigDecimal,
        benefits: String
    ): SubscriptionPlanDao

    suspend fun updatePlan(
        planId: Int,
        name: String?,
        description: String?,
        price: BigDecimal?,
        benefits: String?,
        isActive: Boolean?
    ): Boolean

    suspend fun deletePlan(planId: Int): Boolean

    suspend fun deactivatePlansByCreator(creatorId: Int): Int

    // 플랜 조회
    suspend fun findPlanById(planId: Int): SubscriptionPlanDao?
    suspend fun findPlansByIds(planIds: List<Int>): List<SubscriptionPlanDao>
    suspend fun findPlansByCreator(creatorId: Int, page: Int, limit: Int): List<SubscriptionPlanDao>
    suspend fun findActivePlansByCreator(creatorId: Int, page: Int, limit: Int): List<SubscriptionPlanDao>

    // 카운트
    suspend fun countPlansByCreator(creatorId: Int): Int
    suspend fun countActivePlansByCreator(creatorId: Int): Int
}
