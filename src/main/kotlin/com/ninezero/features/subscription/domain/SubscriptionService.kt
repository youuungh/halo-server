package com.ninezero.features.subscription.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.subscription.SubscriptionDao
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.subscription.presentation.models.request.SubscribeRequest
import com.ninezero.features.subscription.presentation.models.response.*
import com.ninezero.features.subscription.toSubscriptionResponse
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import com.ninezero.features.user.toUserSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** 구독 만료·자동 갱신 일괄 처리 결과 */
data class SubscriptionBatchResult(
    val successCount: Int,
    val failCount: Int,
    val unknownCount: Int = 0
)

private data class CancelScheduledNotice(
    val creatorId: Int,
    val subscriberUsername: String,
    val planName: String,
    val expiresOn: LocalDate
)

private data class CreatedSubscription(
    val subscriptionId: Int,
    val creator: UserSummaryResponse,
    val username: String,
    val planName: String,
    val planPrice: String
)

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
        val created = query {
            // 유저 잠금으로 동시 신청 차단
            val user = UserDao.find { UserTable.id eq userId }.forUpdate().singleOrNull()
                ?: throw UserNotFoundException(userId)

            val plan = planRepository.findPlanById(request.planId)
                ?: throw SubscriptionPlanNotFoundException(request.planId)

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

            // 중복 구독 확인
            if (subscriptionRepository.findActiveSubscription(userId, plan.creatorId) != null) {
                throw SubscriptionAlreadyActiveException()
            }

            val now = nowUtc()
            val expiresAt = now.date.plus(DatePeriod(days = Constants.Subscription.SUBSCRIPTION_DURATION_DAYS))
                .atTime(now.hour, now.minute, now.second, now.nanosecond)

            val subscription = subscriptionRepository.createSubscription(
                userId = userId,
                creatorId = plan.creatorId,
                planId = plan.id.value,
                startedAt = now,
                expiresAt = expiresAt,
                autoRenew = request.autoRenew
            )

            CreatedSubscription(
                subscriptionId = subscription.id.value,
                creator = planCreator.toUserSummaryResponse(),
                username = user.username,
                planName = plan.name,
                planPrice = plan.price.toAmountString()
            )
        }
        val subscriptionId = created.subscriptionId

        // 결제 실패 시 생성된 구독 롤백
        try {
            subscriptionPaymentService.processInitialPayment(
                subscriptionId = subscriptionId,
                userId = userId
            )
        } catch (e: PaymentResultUnknownException) {
            // 청구 불명은 구독을 두고 정리 작업이 확정
            throw e
        } catch (e: Exception) {
            query { subscriptionRepository.deleteSubscription(subscriptionId) }
            throw e
        }

        // 구독 반영을 위한 유저 viewer별 캐시 무효화
        cacheService.deletePattern(CacheKeys.Patterns.userScoped(userId))

        val response = query {
            val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                ?: throw SubscriptionNotFoundException(subscriptionId)
            subscription.toSubscriptionResponse(
                creator = created.creator,
                planName = created.planName,
                planPrice = created.planPrice
            )
        }

        cacheService.delete(CacheKeys.profile(created.creator.id))  // 크리에이터 프로필 구독자 수

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendNewSubscriberNotification(
                    creatorId = created.creator.id,
                    subscriberId = userId,
                    subscriberUsername = created.username,
                    planName = created.planName
                )
            } catch (e: Exception) {
                logger.error("구독 알림 전송 실패: subscriptionId=$subscriptionId, error=${e.message}", e)
            }
        }

        return response
    }

    suspend fun upgradeSubscription(userId: Int, request: SubscribeRequest): SubscriptionResponse {
        val newPlanId = request.planId

        val (subscriptionId, diff, planName) = query {
            val newPlan = planRepository.findPlanById(newPlanId)
                ?: throw SubscriptionPlanNotFoundException(newPlanId)
            if (!newPlan.isActive) {
                throw ValidationException(Errors.Subscription.PLAN_NOT_ACTIVE)
            }
            val existing = subscriptionRepository.findActiveSubscription(userId, newPlan.creatorId)
                ?: throw ValidationException(Errors.Subscription.UPGRADE_WITHOUT_SUBSCRIPTION)
            if (existing.planId == newPlanId) {
                throw ValidationException(Errors.Subscription.PLAN_ALREADY_SUBSCRIBED)
            }
            val currentPlan = planRepository.findPlanById(existing.planId)
                ?: throw SubscriptionPlanNotFoundException(existing.planId)
            val diff = newPlan.price - currentPlan.price
            if (diff.signum() <= 0) {
                throw ValidationException(Errors.Subscription.UPGRADE_ONLY_TO_HIGHER_PLAN)
            }
            Triple(existing.id.value, diff, newPlan.name)
        }

        // 만료일 유지한 차액 청구·플랜 변경
        subscriptionPaymentService.chargeUpgrade(
            subscriptionId = subscriptionId,
            userId = userId,
            newPlanId = newPlanId,
            diffAmount = diff,
            planName = planName
        )

        cacheService.deletePattern(CacheKeys.Patterns.userScoped(userId))

        return query {
            val plan = planRepository.findPlanById(newPlanId)
                ?: throw SubscriptionPlanNotFoundException(newPlanId)
            val creator = userRepository.findUserById(plan.creatorId)
                ?: throw UserNotFoundException(plan.creatorId)
            val subscription = subscriptionRepository.findSubscriptionById(subscriptionId)
                ?: throw SubscriptionNotFoundException(subscriptionId)
            subscription.toSubscriptionResponse(
                creator = creator.toUserSummaryResponse(),
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
            // 상태 필터와 무관한 활성 구독 수
            val activeCount = subscriptionRepository.countUserSubscriptions(userId, SubscriptionStatus.ACTIVE)

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
                    creator = creator.toUserSummaryResponse(),
                    planName = plan.name,
                    planPrice = plan.price.toAmountString()
                )
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
                userMap[id]?.toUserSummaryResponse(
                    isFollowing = followStatusMap[id]
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(subscriberSummaries, pagination)
        }
    }

    suspend fun checkSubscriptionStatus(userId: Int, creatorId: Int): SubscriptionStatusResponse {
        return query {
            val subscription = subscriptionRepository.findActiveSubscription(userId, creatorId)
                ?: return@query SubscriptionStatusResponse(isSubscribed = false, autoRenew = false)

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
        val notice = query {
            val subscription = verifySubscriptionOwnership(subscriptionId, userId, Errors.Subscription.SUBSCRIPTION_CANCEL_PERMISSION_DENIED)

            if (subscription.status != SubscriptionStatus.ACTIVE || !subscription.autoRenew) {
                throw ValidationException(Errors.Subscription.SUBSCRIPTION_ALREADY_CANCELLED)
            }

            // 자동갱신만 비활성화
            subscriptionRepository.updateAutoRenew(subscriptionId, false)

            val plan = planRepository.findPlanById(subscription.planId)
                ?: throw SubscriptionPlanNotFoundException(subscription.planId)
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            CancelScheduledNotice(subscription.creatorId, user.username, plan.name, subscription.expiresAt.date)
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendSubscriptionCancelScheduledNotification(
                    creatorId = notice.creatorId,
                    subscriberId = userId,
                    subscriberUsername = notice.subscriberUsername,
                    planName = notice.planName,
                    expiresOn = notice.expiresOn
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

    /** 기한 지난 구독 만료와 알림 */
    suspend fun expireSubscriptions(): SubscriptionBatchResult {
        val expiredSubscriptions = query {
            subscriptionRepository.findExpiredSubscriptions()
        }
        logger.info("만료된 구독 {}건 처리 중", expiredSubscriptions.size)

        var successCount = 0
        var failCount = 0

        expiredSubscriptions.forEach { subscription ->
            try {
                query {
                    subscriptionRepository.expireSubscription(subscription.id.value)
                }

                notifySubscriber(subscription) { creatorUsername, planName ->
                    notificationService.sendSubscriptionExpiredNotification(
                        subscription.userId, subscription.id.value, creatorUsername, planName
                    )
                }

                successCount++
                logger.debug("구독 만료 처리 완료: subscriptionId={}", subscription.id.value)
            } catch (e: Exception) {
                failCount++
                logger.error("구독 만료 처리 실패: subscriptionId=${subscription.id.value}, error=${e.message}", e)
            }
        }

        return SubscriptionBatchResult(successCount, failCount)
    }

    /** 자동 갱신 대상 청구와 결과 알림 */
    suspend fun renewSubscriptions(): SubscriptionBatchResult {
        val subscriptionsForRenewal = query {
            subscriptionRepository.findSubscriptionsForRenewal()
        }
        logger.info("자동 갱신 대상 구독 {}건 처리 중", subscriptionsForRenewal.size)

        var successCount = 0
        var failCount = 0
        var unknownCount = 0

        subscriptionsForRenewal.forEach { subscription ->
            try {
                when (subscriptionPaymentService.processRenewalPayment(subscriptionId = subscription.id.value)) {
                    RenewalResult.SUCCESS -> {  // 연장은 결제 기록 때 처리됨
                        // 달마다 다른 중복 제거 키용 날짜
                        val nextBillingDate = (query {
                            subscriptionRepository.findSubscriptionById(subscription.id.value)?.expiresAt
                        } ?: subscription.expiresAt).toBusinessDate()

                        notifySubscriber(subscription) { creatorUsername, planName ->
                            notificationService.sendSubscriptionRenewedNotification(
                                subscription.userId, subscription.id.value, creatorUsername, planName, nextBillingDate
                            )
                        }

                        successCount++
                        logger.debug("구독 자동 갱신 성공: subscriptionId={}", subscription.id.value)
                    }

                    RenewalResult.UNKNOWN -> {
                        // 결과 불명은 autoRenew 유지
                        unknownCount++
                        logger.warn("구독 갱신 결과 불명 — 다음 주기 재시도: subscriptionId={}", subscription.id.value)
                    }

                    RenewalResult.SKIPPED -> {
                        // 대상 조회 뒤 해지된 구독, 청구·알림 없음
                        logger.debug("구독 갱신 건너뜀(해지됨): subscriptionId={}", subscription.id.value)
                    }

                    RenewalResult.DECLINED -> {
                        query {
                            subscriptionRepository.updateAutoRenew(subscription.id.value, false)
                        }

                        notifySubscriber(subscription) { creatorUsername, planName ->
                            notificationService.sendSubscriptionRenewalFailedNotification(
                                subscription.userId, subscription.id.value, creatorUsername, planName, subscription.expiresAt.toBusinessDate()
                            )
                        }

                        failCount++
                        logger.warn("구독 자동 갱신 실패: subscriptionId={}", subscription.id.value)
                    }
                }
            } catch (e: Exception) {
                failCount++
                logger.error("구독 자동 갱신 처리 실패: subscriptionId=${subscription.id.value}, error=${e.message}", e)
            }
        }

        return SubscriptionBatchResult(successCount, failCount, unknownCount)
    }

    // 접근 판정
    suspend fun getMyTierForCreator(
        userId: Int,
        creatorId: Int
    ): UserTierResponse {
        return query {
            userRepository.findUserById(creatorId)
                ?: throw UserNotFoundException(creatorId)

            val subscription = subscriptionRepository.findActiveSubscription(userId, creatorId)
                ?: return@query UserTierResponse(tier = SubscriptionPlanTier.FREE, isSubscribed = false)

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

    /** 구독자 알림에 필요한 크리에이터·플랜 이름 조회, 발송은 트랜잭션 밖 */
    private suspend fun notifySubscriber(
        subscription: SubscriptionDao,
        send: suspend (creatorUsername: String, planName: String) -> Unit
    ) {
        val (creatorUsername, planName) = query {
            val plan = planRepository.findPlanById(subscription.planId)
            val creator = userRepository.findUserById(subscription.creatorId)
            if (plan != null && creator != null) creator.username to plan.name else null
        } ?: return

        send(creatorUsername, planName)
    }

    /** 저장된 UTC 시각을 업무 시간대 날짜로 */
    private fun LocalDateTime.toBusinessDate(): LocalDate =
        toInstant(TimeZone.UTC).toLocalDateTime(BUSINESS_TIMEZONE).date
}
