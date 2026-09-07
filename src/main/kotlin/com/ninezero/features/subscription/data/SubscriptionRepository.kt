package com.ninezero.features.subscription.data

import com.ninezero.core.common.config.SubscriptionStatus
import com.ninezero.core.database.entities.subscription.SubscriptionDao
import kotlinx.datetime.LocalDateTime

interface SubscriptionRepository {

    // 구독 생성/취소/수정/삭제
    suspend fun createSubscription(
        userId: Int,
        creatorId: Int,
        planId: Int,
        startedAt: LocalDateTime,
        expiresAt: LocalDateTime,
        autoRenew: Boolean
    ): SubscriptionDao

    suspend fun cancelSubscription(subscriptionId: Int): Pair<SubscriptionDao, Int>
    suspend fun deleteSubscription(subscriptionId: Int)
    suspend fun updateAutoRenew(subscriptionId: Int, autoRenew: Boolean): Boolean
    suspend fun updateSubscriptionPlan(subscriptionId: Int, planId: Int): Boolean

    // 구독 조회
    suspend fun findSubscriptionById(subscriptionId: Int): SubscriptionDao?
    suspend fun findUserSubscriptions(userId: Int, status: SubscriptionStatus?, page: Int, limit: Int): List<SubscriptionDao>
    suspend fun findActiveSubscription(userId: Int, creatorId: Int): SubscriptionDao?
    suspend fun findActiveSubscriptionsByCreators(userId: Int, creatorIds: List<Int>): List<SubscriptionDao>
    suspend fun checkSubscriptionAccess(userId: Int, creatorId: Int, planId: Int?): Boolean

    // 구독자 조회
    suspend fun findCreatorSubscribers(creatorId: Int, page: Int, limit: Int): List<SubscriptionDao>
    suspend fun findPlanSubscribers(planId: Int, page: Int, limit: Int): List<SubscriptionDao>

    // 검색 포함 구독자 조회/카운트
    suspend fun findCreatorSubscribersWithSearch(creatorId: Int, search: String, page: Int, limit: Int): List<Int>
    suspend fun countCreatorSubscribersWithSearch(creatorId: Int, search: String): Int

    // 카운트
    suspend fun countUserSubscriptions(userId: Int, status: SubscriptionStatus?): Int
    suspend fun countCreatorSubscribers(creatorId: Int): Int
    suspend fun countPlanSubscribers(planId: Int): Int

    // 구독 만료 처리
    suspend fun findExpiredSubscriptions(): List<SubscriptionDao>
    suspend fun expireSubscription(subscriptionId: Int): Boolean

    // 탈퇴 정리
    suspend fun expireAllActiveByUser(userId: Int): Int

    // 구독 갱신
    suspend fun findSubscriptionsForRenewal(): List<SubscriptionDao>
    suspend fun extendSubscription(subscriptionId: Int, newExpiresAt: LocalDateTime): Boolean
}
