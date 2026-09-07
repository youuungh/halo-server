package com.ninezero.plugins

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.config.NotificationTargetType
import com.ninezero.core.common.config.NotificationType
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.features.commerce.domain.OrderService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.subscription.domain.RenewalResult
import com.ninezero.features.subscription.domain.SubscriptionPaymentService
import com.ninezero.features.user.data.RefreshTokenRepository
import com.ninezero.features.user.data.UserRepository
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val NOTIFICATION_RETENTION_DAYS = 90
private val SCHEDULER_TIMEZONE: TimeZone by lazy {
    val tz = DotenvConfig.getOrDefault("SCHEDULER_TIMEZONE", "Asia/Seoul")
    TimeZone.of(tz)
}

private const val STALE_DEVICE_TOKEN_DAYS = 30

fun Application.configureScheduler() {
    val pointService by inject<PointService>()
    val subscriptionRepository by inject<SubscriptionRepository>()
    val subscriptionPlanRepository by inject<SubscriptionPlanRepository>()
    val userRepository by inject<UserRepository>()
    val subscriptionPaymentService by inject<SubscriptionPaymentService>()
    val notificationService by inject<NotificationService>()
    val orderService by inject<OrderService>()
    val refreshTokenRepository by inject<RefreshTokenRepository>()

    launch {
        schedulePointExpiration(pointService)
    }

    launch {
        scheduleStalePendingOrderReconcile(orderService)
    }

    launch {
        scheduleSubscriptionExpiration(
            subscriptionRepository,
            subscriptionPlanRepository,
            userRepository,
            notificationService
        )
    }

    launch {
        scheduleSubscriptionRenewal(
            subscriptionRepository,
            subscriptionPlanRepository,
            userRepository,
            subscriptionPaymentService,
            notificationService
        )
    }

    launch {
        scheduleNotificationCleanup(notificationService)
    }

    launch {
        scheduleExpiredTokenCleanup(refreshTokenRepository)
    }

    launch {
        scheduleStaleDeviceTokenCleanup(userRepository)
    }

    log.info("스케줄러가 시작되었습니다.")
}

/** 매일 새벽 3시에 실행 */
private suspend fun Application.schedulePointExpiration(pointService: PointService) {
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 3)
            delay(delayUntilNext)

            log.info("[스케줄러] 포인트 만료 처리 시작: ${Clock.System.now()}")
            val expiredCount = pointService.expirePoints()
            log.info("[스케줄러] 포인트 만료 처리 완료: ${expiredCount}건 처리됨")

        } catch (e: Exception) {
            log.error("[스케줄러] 포인트 만료 처리 중 오류 발생: ${e.message}", e)
            delay(1.hours)
        }
    }
}

/** 매일 새벽 4시에 실행 */
private suspend fun Application.scheduleSubscriptionExpiration(
    subscriptionRepository: SubscriptionRepository,
    planRepository: SubscriptionPlanRepository,
    userRepository: UserRepository,
    notificationService: NotificationService
) {
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 4)
            delay(delayUntilNext)

            log.info("[스케줄러] 구독 만료 처리 시작: ${Clock.System.now()}")

            val expiredSubscriptions = query {
                subscriptionRepository.findExpiredSubscriptions()
            }

            log.info("[스케줄러] 만료된 구독 ${expiredSubscriptions.size}건 처리 중")

            var successCount = 0
            var failCount = 0

            expiredSubscriptions.forEach { subscription ->
                try {
                    query {
                        subscriptionRepository.expireSubscription(subscription.id.value)
                    }

                    sendSubscriptionNotification(
                        planRepository = planRepository,
                        userRepository = userRepository,
                        notificationService = notificationService,
                        planId = subscription.planId,
                        creatorId = subscription.creatorId,
                        userId = subscription.userId,
                        targetId = subscription.id.value,
                        title = "구독 만료"
                    ) { creatorUsername, planName ->
                        "${creatorUsername}님의 $planName 플랜 구독이 만료되었습니다."
                    }

                    successCount++
                    log.debug("[스케줄러] 구독 만료 처리 완료: subscriptionId=${subscription.id.value}")

                } catch (e: Exception) {
                    failCount++
                    log.error("[스케줄러] 구독 만료 처리 실패: subscriptionId=${subscription.id.value}, error=${e.message}", e)
                }
            }

            log.info("[스케줄러] 구독 만료 처리 완료: 성공 ${successCount}건, 실패 ${failCount}건")

        } catch (e: Exception) {
            log.error("[스케줄러] 구독 만료 처리 중 오류 발생: ${e.message}", e)
            delay(1.hours)
        }
    }
}

/** 매 시간마다 실행 */
private suspend fun Application.scheduleSubscriptionRenewal(
    subscriptionRepository: SubscriptionRepository,
    planRepository: SubscriptionPlanRepository,
    userRepository: UserRepository,
    subscriptionPaymentService: SubscriptionPaymentService,
    notificationService: NotificationService
) {
    while (true) {
        try {
            log.info("[스케줄러] 구독 자동 갱신 처리 시작: ${Clock.System.now()}")

            val subscriptionsForRenewal = query {
                subscriptionRepository.findSubscriptionsForRenewal()
            }

            log.info("[스케줄러] 자동 갱신 대상 구독 ${subscriptionsForRenewal.size}건 처리 중")

            var successCount = 0
            var failCount = 0
            var unknownCount = 0

            subscriptionsForRenewal.forEach { subscription ->
                try {
                    val renewalResult = subscriptionPaymentService.processRenewalPayment(
                        subscriptionId = subscription.id.value
                    )

                    if (renewalResult == RenewalResult.SUCCESS) {
                        val newExpiresAt = subscription.expiresAt.date
                            .plus(DatePeriod(days = Constants.Subscription.SUBSCRIPTION_DURATION_DAYS))
                            .atTime(subscription.expiresAt.hour, subscription.expiresAt.minute, subscription.expiresAt.second, subscription.expiresAt.nanosecond)

                        query {
                            subscriptionRepository.extendSubscription(
                                subscriptionId = subscription.id.value,
                                newExpiresAt = newExpiresAt
                            )
                        }

                        sendSubscriptionNotification(
                            planRepository = planRepository,
                            userRepository = userRepository,
                            notificationService = notificationService,
                            planId = subscription.planId,
                            creatorId = subscription.creatorId,
                            userId = subscription.userId,
                            targetId = subscription.id.value,
                            title = "구독 자동 갱신"
                        ) { creatorUsername, planName ->
                            "${creatorUsername}님의 ${planName} 플랜이 자동 갱신되었습니다."
                        }

                        successCount++
                        log.debug("[스케줄러] 구독 자동 갱신 성공: subscriptionId=${subscription.id.value}")

                    } else if (renewalResult == RenewalResult.UNKNOWN) {
                        // 결과 불명은 autoRenew 유지
                        unknownCount++
                        log.warn("[스케줄러] 구독 갱신 결과 불명 — 다음 주기 재시도: subscriptionId=${subscription.id.value}")

                    } else {
                        query {
                            subscriptionRepository.updateAutoRenew(subscription.id.value, false)
                        }

                        sendSubscriptionNotification(
                            planRepository = planRepository,
                            userRepository = userRepository,
                            notificationService = notificationService,
                            planId = subscription.planId,
                            creatorId = subscription.creatorId,
                            userId = subscription.userId,
                            targetId = subscription.id.value,
                            title = "구독 자동 갱신 실패"
                        ) { creatorUsername, planName ->
                            "${creatorUsername}님의 ${planName} 플랜 자동 갱신에 실패했습니다. 결제 정보를 확인해주세요."
                        }

                        failCount++
                        log.warn("[스케줄러] 구독 자동 갱신 실패: subscriptionId=${subscription.id.value}")
                    }

                } catch (e: Exception) {
                    failCount++
                    log.error("[스케줄러] 구독 자동 갱신 처리 실패: subscriptionId=${subscription.id.value}, error=${e.message}", e)
                }
            }

            log.info("[스케줄러] 구독 자동 갱신 처리 완료: 성공 ${successCount}건, 실패 ${failCount}건, 결과불명 ${unknownCount}건")

        } catch (e: Exception) {
            log.error("[스케줄러] 구독 자동 갱신 처리 중 오류 발생: ${e.message}", e)
        }

        delay(1.hours)
    }
}

/** 매일 새벽 5시에 실행 */
private suspend fun Application.scheduleNotificationCleanup(
    notificationService: NotificationService
) {
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 5)
            delay(delayUntilNext)

            log.info("[스케줄러] 오래된 알림 정리 시작: ${Clock.System.now()}")
            val deactivatedCount = notificationService.cleanupOldNotifications(NOTIFICATION_RETENTION_DAYS)
            log.info("[스케줄러] 오래된 알림 정리 완료: ${deactivatedCount}건 비활성화")

        } catch (e: Exception) {
            log.error("[스케줄러] 오래된 알림 정리 중 오류 발생: ${e.message}", e)
            delay(1.hours)
        }
    }
}

/** 매일 새벽 6시 실행 */
private suspend fun Application.scheduleStaleDeviceTokenCleanup(
    userRepository: UserRepository
) {
    while (true) {  // 알림 발송 후에 실행해야 함
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 6)
            delay(delayUntilNext)

            log.info("[스케줄러] 오래된 기기 등록 정리 시작: ${Clock.System.now()}")
            val threshold = nowUtc().toInstant(TimeZone.UTC)
                .minus(STALE_DEVICE_TOKEN_DAYS.days)
                .toLocalDateTime(TimeZone.UTC)
            val deletedCount = query { userRepository.deleteStaleDeviceTokens(threshold) }
            log.info("[스케줄러] 오래된 기기 등록 정리 완료: ${deletedCount}건 삭제")

        } catch (e: Exception) {
            log.error("[스케줄러] 오래된 기기 등록 정리 중 오류 발생: ${e.message}", e)
            delay(1.hours)
        }
    }
}

/** 매일 새벽 2시 실행 */
private suspend fun Application.scheduleExpiredTokenCleanup(
    refreshTokenRepository: RefreshTokenRepository
) {
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 2)
            delay(delayUntilNext)

            log.info("[스케줄러] 만료 리프레시 토큰 정리 시작: ${Clock.System.now()}")
            val deletedCount = query { refreshTokenRepository.deleteExpired(nowUtc()) }
            log.info("[스케줄러] 만료 리프레시 토큰 정리 완료: ${deletedCount}건 삭제")

        } catch (e: Exception) {
            log.error("[스케줄러] 만료 리프레시 토큰 정리 중 오류 발생: ${e.message}", e)
            delay(1.hours)
        }
    }
}

/** 15분마다 방치 주문 보정 */
private suspend fun Application.scheduleStalePendingOrderReconcile(orderService: OrderService) {
    // 첫 실행 전 잠시 대기
    delay(2.minutes)
    while (true) {
        try {
            val processed = orderService.reconcileStalePendingOrders(olderThanMinutes = 30)
            if (processed > 0) {
                log.info("[스케줄러] 미결제 주문 정리 완료: ${processed}건 처리")
            }
        } catch (e: Exception) {
            log.error("[스케줄러] 미결제 주문 정리 중 오류 발생: ${e.message}", e)
        }
        delay(15.minutes)
    }
}

/** 구독 시스템 알림 전송 */
private suspend fun sendSubscriptionNotification(
    planRepository: SubscriptionPlanRepository,
    userRepository: UserRepository,
    notificationService: NotificationService,
    planId: Int,
    creatorId: Int,
    userId: Int,
    targetId: Int,
    title: String,
    messageBuilder: (creatorUsername: String, planName: String) -> String
) {
    // 알림 전송은 트랜잭션 밖
    val info = query {
        val plan = planRepository.findPlanById(planId)
        val creator = userRepository.findUserById(creatorId)
        if (plan != null && creator != null) creator.username to plan.name else null
    } ?: return

    notificationService.sendNotificationToUser(
        userId = userId,
        type = NotificationType.SYSTEM_ANNOUNCEMENT,
        title = title,
        message = messageBuilder(info.first, info.second),
        targetType = NotificationTargetType.SUBSCRIPTION,
        targetId = targetId
    )
}

private fun Application.calcDelayUntilTime(hour: Int): Long {
    val now = Clock.System.now()
    val nowLocal = now.toLocalDateTime(SCHEDULER_TIMEZONE)

    val targetTime = LocalDateTime(
        year = nowLocal.year,
        month = nowLocal.month,
        day = nowLocal.day,
        hour = hour,
        minute = 0,
        second = 0,
        nanosecond = 0
    )

    var targetInstant = targetTime.toInstant(SCHEDULER_TIMEZONE)

    if (now >= targetInstant) {
        targetInstant = targetInstant.plus(1.days)
    }

    val delayMillis = targetInstant.toEpochMilliseconds() - now.toEpochMilliseconds()

    log.info("[스케줄러] 다음 ${hour}시(${SCHEDULER_TIMEZONE.id}) 실행 예정: ${targetInstant.toLocalDateTime(SCHEDULER_TIMEZONE)} (${delayMillis}ms 후)")

    return delayMillis
}