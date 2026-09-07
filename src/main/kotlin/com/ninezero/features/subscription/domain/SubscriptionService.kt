package com.ninezero.features.subscription.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.subscription.presentation.models.request.SubscribeRequest
import com.ninezero.features.subscription.presentation.models.response.*
import com.ninezero.features.subscription.toSubscriptionResponse
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import java.math.BigDecimal

class SubscriptionService(
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val userRepository: UserRepository,
    private val followRepository: FollowRepository,
    private val notificationService: NotificationService,
    private val subscriptionPaymentService: SubscriptionPaymentService,
    private val cacheService: CacheService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    // 구독·업그레이드
    suspend fun subscribe(userId: Int, request: SubscribeRequest): SubscriptionResponse {
        val planId = request.planId
        val autoRenew = request.autoRenew

        query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            val plan = planRepository.findPlanById(planId)
                ?: throw SubscriptionPlanNotFoundException(planId)

            if (!plan.isActive) {
                throw ValidationException(Errors.Subscription.PLAN_NOT_ACTIVE)
            }

            // 크리에이터 해제된 대상 구독 차단
            val planCreator = userRepository.findUserById(plan.creatorId)
                ?: throw UserNotFoundException(plan.creatorId)
            if (planCreator.role != UserRole.CREATOR && planCreator.role != UserRole.ADMIN) {
                throw ValidationException(Errors.Subscription.PLAN_NOT_ACTIVE)
            }

            if (plan.creatorId == userId) {
                throw ValidationException(Errors.Subscription.CANNOT_SUBSCRIBE_TO_SELF)
            }
        }

        val subscriptionId = query {
            val plan = planRepository.findPlanById(planId)
                ?: throw SubscriptionPlanNotFoundException(planId)

            // 중복 구독 확인
            if (subscriptionRepository.findActiveSubscription(userId, plan.creatorId) != null) {
                throw SubscriptionAlreadyActiveException()
            }

            val now = nowUtc()
            val expiresAt = now.date.plus(DatePeriod(days = Constants.Subscription.SUBSCRIPTION_DURATION_DAYS))
                .atTime(now.hour, now.minute, now.second, now.nanosecond)

            subscriptionRepository.createSubscription(
                userId = userId,
                creatorId = plan.creatorId,
                planId = plan.id.value,
                startedAt = now,
                expiresAt = expiresAt,
                autoRenew = autoRenew
            ).id.value
        }

        // 결제 실패 시 생성된 구독 롤백
        try {
            subscriptionPaymentService.processInitialPayment(
                subscriptionId = subscriptionId,
                userId = userId
            )
        } catch (e: Exception) {
            query { subscriptionRepository.deleteSubscription(subscriptionId) }
            throw e
        }

        // 구독 반영을 위한 유저 viewer별 캐시 무효화
        cacheService.deletePattern(CacheKeys.Patterns.userScoped(userId))

        val (response, notificationData) = query {
            val plan = planRepository.findPlanById(planId)
                ?: throw SubscriptionPlanNotFoundException(planId)
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)
            val creator = userRepository.findUserById(plan.creatorId)
                ?: throw UserNotFoundException(plan.creatorId)
            val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                ?: throw SubscriptionNotFoundException(subscriptionId)

            val creatorSummary = creator.toSummaryResponse()
            val subscriptionResponse = subscription.toSubscriptionResponse(
                creator = creatorSummary,
                planName = plan.name,
                planPrice = plan.price.toAmountString()
            )

            data class NotificationData(val creatorId: Int, val username: String, val planName: String)
            subscriptionResponse to NotificationData(plan.creatorId, user.username, plan.name)
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendNotificationToUser(
                    userId = notificationData.creatorId,
                    type = NotificationType.SYSTEM_ANNOUNCEMENT,
                    title = "새로운 구독자",
                    message = "${notificationData.username}님이 ${notificationData.planName} 플랜을 구독했습니다.",
                    // 크리에이터 수신
                    targetType = null,
                    targetId = null
                )
            } catch (e: Exception) {
                logger.error("구독 알림 전송 실패: subscriptionId=$subscriptionId, error=${e.message}", e)
            }
        }

        return response
    }

    suspend fun upgradeSubscription(userId: Int, request: SubscribeRequest): SubscriptionResponse {
        val newPlanId = request.planId

        val prepared: Triple<Int, BigDecimal, String> = query {
            val newPlan = planRepository.findPlanById(newPlanId)
                ?: throw SubscriptionPlanNotFoundException(newPlanId)
            if (!newPlan.isActive) {
                throw ValidationException(Errors.Subscription.PLAN_NOT_ACTIVE)
            }
            val existing = subscriptionRepository.findActiveSubscription(userId, newPlan.creatorId)
                ?: throw ValidationException("구독 중이 아니라 업그레이드할 수 없습니다.")
            if (existing.planId == newPlanId) {
                throw ValidationException("이미 해당 플랜을 구독 중입니다.")
            }
            val currentPlan = planRepository.findPlanById(existing.planId)
                ?: throw SubscriptionPlanNotFoundException(existing.planId)
            val diff = newPlan.price - currentPlan.price
            if (diff.signum() <= 0) {
                throw ValidationException("상위 플랜으로만 업그레이드할 수 있습니다.")
            }
            Triple(existing.id.value, diff, newPlan.name)
        }
        val (subscriptionId, diff, planName) = prepared

        // 차액 청구
        subscriptionPaymentService.chargeUpgrade(
            subscriptionId = subscriptionId,
            userId = userId,
            newPlanId = newPlanId,
            diffAmount = diff,
            planName = planName
        )

        // 플랜 변경, 만료일 유지
        query { subscriptionRepository.updateSubscriptionPlan(subscriptionId, newPlanId) }

        cacheService.deletePattern(CacheKeys.Patterns.userScoped(userId))

        return query {
            val plan = planRepository.findPlanById(newPlanId)
                ?: throw SubscriptionPlanNotFoundException(newPlanId)
            val creator = userRepository.findUserById(plan.creatorId)
                ?: throw UserNotFoundException(plan.creatorId)
            val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                ?: throw SubscriptionNotFoundException(subscriptionId)
            subscription.toSubscriptionResponse(
                creator = creator.toSummaryResponse(),
                planName = plan.name,
                planPrice = plan.price.toAmountString()
            )
        }
    }

    // 구독 조회
    suspend fun getMySubscriptions(
        userId: Int,
        status: SubscriptionStatus?,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): SubscriptionListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val subscriptions = subscriptionRepository.findUserSubscriptions(
                userId, status, validPage, validLimit
            )
            val totalCount = subscriptionRepository.countUserSubscriptions(userId, status)

            if (subscriptions.isEmpty()) {
                val pagination = PaginationInfo(validPage, validLimit, totalCount)
                return@query SubscriptionListResponse(
                    subscriptions = createPagedResponse(emptyList(), pagination),
                    activeCount = 0
                )
            }

            val creatorIds = subscriptions.map { it.creatorId }.distinct()
            val planIds = subscriptions.map { it.planId }.distinct()

            val creators = userRepository.findUsersByIds(creatorIds)
                .associateBy { it.id.value }
            val plans = planRepository.findPlansByIds(planIds)
                .associateBy { it.id.value }

            val subscriptionResponses = subscriptions.mapNotNull { subscription ->
                val creator = creators[subscription.creatorId] ?: return@mapNotNull null
                val plan = plans[subscription.planId] ?: return@mapNotNull null

                subscription.toSubscriptionResponse(
                    creator = creator.toSummaryResponse(),
                    planName = plan.name,
                    planPrice = plan.price.toAmountString()
                )
            }

            val activeCount = subscriptionResponses.count {
                it.status == SubscriptionStatus.ACTIVE
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            SubscriptionListResponse(
                subscriptions = createPagedResponse(subscriptionResponses, pagination),
                activeCount = activeCount
            )
        }
    }

    suspend fun getMySubscribers(
        creatorId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        search: String? = null
    ): SubscriberListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val creator = userRepository.findUserById(creatorId)
                ?: throw UserNotFoundException(creatorId)

            if (creator.role != UserRole.CREATOR && creator.role != UserRole.ADMIN) {
                throw CreatorOnlyException()
            }

            val (subscriberIds, totalCount) = if (search.isNullOrBlank()) {
                val subscriptions = subscriptionRepository.findCreatorSubscribers(creatorId, validPage, validLimit)
                val count = subscriptionRepository.countCreatorSubscribers(creatorId)
                subscriptions.map { it.userId }.distinct() to count
            } else {
                val ids = subscriptionRepository.findCreatorSubscribersWithSearch(creatorId, search, validPage, validLimit)
                val count = subscriptionRepository.countCreatorSubscribersWithSearch(creatorId, search)
                ids to count
            }

            val users = userRepository.findUsersByIds(subscriberIds)
            val userMap = users.associateBy { it.id.value }

            val followStatusMap = followRepository.checkMultipleFollowStatus(creatorId, subscriberIds)

            val subscriberSummaries = subscriberIds.mapNotNull { id ->
                userMap[id]?.toSummaryResponse(
                    isFollowing = followStatusMap[id]
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(subscriberSummaries, pagination)
        }
    }

    suspend fun getSubscriptionById(userId: Int, subscriptionId: Int): SubscriptionResponse {
        return query {
            val subscription = verifySubscriptionOwnership(subscriptionId, userId, Errors.Common.PERMISSION_DENIED)

            val creator = userRepository.findUserById(subscription.creatorId)
                ?: throw UserNotFoundException(subscription.creatorId)

            val plan = planRepository.findPlanById(subscription.planId)
                ?: throw SubscriptionPlanNotFoundException(subscription.planId)

            subscription.toSubscriptionResponse(
                creator = creator.toSummaryResponse(),
                planName = plan.name,
                planPrice = plan.price.toAmountString()
            )
        }
    }

    suspend fun checkSubscriptionStatus(userId: Int, creatorId: Int): SubscriptionStatusResponse {
        return query {
            val subscription = subscriptionRepository.findActiveSubscription(userId, creatorId)
                ?: return@query SubscriptionStatusResponse(
                    isSubscribed = false,
                    subscriptionId = null,
                    planId = null,
                    planName = null,
                    expiresAt = null,
                    daysRemaining = null,
                    autoRenew = false
                )

            val plan = planRepository.findPlanById(subscription.planId)

            SubscriptionStatusResponse(
                isSubscribed = true,
                subscriptionId = subscription.id.value,
                planId = subscription.planId,
                planName = plan?.name,
                expiresAt = subscription.expiresAt,
                daysRemaining = calcDaysRemaining(
                    subscription.expiresAt,
                    nowUtc()
                ),
                autoRenew = subscription.autoRenew
            )
        }
    }

    /** 자동갱신 해제 */
    suspend fun cancelSubscription(userId: Int, subscriptionId: Int): String {
        val (creatorId, expiresAt, planName) = query {
            val subscription = verifySubscriptionOwnership(
                subscriptionId,
                userId,
                Errors.Subscription.SUBSCRIPTION_CANCEL_PERMISSION_DENIED
            )

            if (subscription.status != SubscriptionStatus.ACTIVE) {
                throw ValidationException(Errors.Subscription.SUBSCRIPTION_ALREADY_CANCELLED)
            }

            if (!subscription.autoRenew) {
                throw ValidationException(Errors.Subscription.SUBSCRIPTION_ALREADY_CANCELLED)
            }

            // 자동갱신만 비활성화
            subscriptionRepository.updateAutoRenew(subscriptionId, false)

            val plan = planRepository.findPlanById(subscription.planId)
                ?: throw SubscriptionPlanNotFoundException(subscription.planId)

            Triple(subscription.creatorId, subscription.expiresAt, plan.name)
        }

        val user = query {
            userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendNotificationToUser(
                    userId = creatorId,
                    type = NotificationType.SYSTEM_ANNOUNCEMENT,
                    title = "구독 취소 예정",
                    message = "${user.username}님이 $planName 플랜 자동 갱신을 취소했습니다. (${expiresAt.date}까지 유지)",
                    // 크리에이터 수신
                    targetType = null,
                    targetId = null
                )
            } catch (e: Exception) {
                logger.error("구독 취소 알림 전송 실패: subscriptionId=$subscriptionId, error=${e.message}", e)
            }
        }

        return Messages.Subscription.SUBSCRIPTION_CANCELLED
    }

    suspend fun updateAutoRenew(
        userId: Int,
        subscriptionId: Int,
        autoRenew: Boolean
    ): String {
        query {
            val subscription = verifySubscriptionOwnership(
                subscriptionId,
                userId,
                Errors.Subscription.SUBSCRIPTION_UPDATE_PERMISSION_DENIED
            )

            if (subscription.status != SubscriptionStatus.ACTIVE) {
                throw ValidationException(Errors.Subscription.SUBSCRIPTION_ONLY_ACTIVE_AUTO_RENEW)  // ACTIVE 상태만 허용
            }

            val updated = subscriptionRepository.updateAutoRenew(subscriptionId, autoRenew)
            if (!updated) {
                throw InternalServerException(Errors.Subscription.SUBSCRIPTION_AUTO_RENEW_UPDATE_FAILED)
            }
        }

        return if (autoRenew) {
            Messages.Subscription.SUBSCRIPTION_AUTO_RENEW_ENABLED
        } else {
            Messages.Subscription.SUBSCRIPTION_AUTO_RENEW_DISABLED
        }
    }

    // 접근 판정
    suspend fun canAccessContent(
        userId: Int,
        creatorId: Int,
        planId: Int?
    ): Boolean {
        return query {
            subscriptionRepository.checkSubscriptionAccess(userId, creatorId, planId)
        }
    }

    suspend fun getMyTierForCreator(
        userId: Int,
        creatorId: Int
    ): UserTierResponse {
        return query {
            userRepository.findUserById(creatorId)
                ?: throw UserNotFoundException(creatorId)

            val subscription = subscriptionRepository.findActiveSubscription(userId, creatorId)
                ?: return@query UserTierResponse(
                    tier = SubscriptionPlanTier.FREE,  // 미구독 시 FREE
                    isSubscribed = false,
                    subscriptionId = null,
                    planName = null,
                    planPrice = null,
                    expiresAt = null,
                    daysRemaining = null
                )

            val plan = planRepository.findPlanById(subscription.planId)
                ?: throw SubscriptionPlanNotFoundException(subscription.planId)

            val daysRemaining = calcDaysRemaining(
                subscription.expiresAt,
                nowUtc()
            )

            UserTierResponse(
                tier = plan.tier,
                isSubscribed = true,
                subscriptionId = subscription.id.value,
                planName = plan.name,
                planPrice = plan.price.toAmountString(),
                expiresAt = subscription.expiresAt,
                daysRemaining = daysRemaining
            )
        }
    }

    private suspend fun verifySubscriptionOwnership(
        subscriptionId: Int,
        userId: Int,
        errorMessage: String
    ) = subscriptionRepository.findSubscriptionById(subscriptionId)
        ?.also { subscription ->
            if (subscription.userId != userId) {
                throw ForbiddenException(errorMessage)
            }
        }
        ?: throw SubscriptionNotFoundException(subscriptionId)
}
