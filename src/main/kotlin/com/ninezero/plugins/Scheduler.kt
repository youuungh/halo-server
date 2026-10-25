package com.ninezero.plugins

import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.BUSINESS_TIMEZONE
import com.ninezero.features.commerce.domain.OrderService
import com.ninezero.features.commerce.domain.OrderShippingService
import com.ninezero.features.coupon.domain.CouponRedemptionService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.subscription.domain.SubscriptionPaymentService
import com.ninezero.features.subscription.domain.SubscriptionService
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.upload.domain.MediaUploadService
import com.ninezero.features.user.domain.AuthService
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val NOTIFICATION_RETENTION_DAYS = 90

private const val STALE_DEVICE_TOKEN_DAYS = 30

private const val EXPIRED_PENDING_SIGNUP_RETENTION_DAYS = 1  // 시간당 발송 상한 창보다 길게

fun Application.configureScheduler() {
    if (!DotenvConfig.getOrDefault("SCHEDULER_ENABLED", "true").toBoolean()) {  // 점검·데이터 이관 중엔 끔
        log.info("스케줄러가 꺼져 있습니다.")
        return
    }

    val pointService by inject<PointService>()
    val couponRedemptionService by inject<CouponRedemptionService>()
    val orderService by inject<OrderService>()
    val orderShippingService by inject<OrderShippingService>()
    val subscriptionPaymentService by inject<SubscriptionPaymentService>()
    val subscriptionService by inject<SubscriptionService>()
    val notificationService by inject<NotificationService>()
    val authService by inject<AuthService>()
    val mediaUploadService by inject<MediaUploadService>()
    val mediaDraftService by inject<MediaDraftService>()

    launch {
        schedulePointExpiration(pointService)
    }

    launch {
        scheduleUserCouponExpiration(couponRedemptionService)
    }

    launch {
        scheduleStalePendingPaymentReconcile(orderService, subscriptionPaymentService)
    }

    launch {
        scheduleSubscriptionExpiration(subscriptionService)
    }

    launch {
        scheduleSubscriptionRenewal(subscriptionService)
    }

    launch {
        scheduleNotificationCleanup(notificationService)
    }

    launch {
        scheduleExpiredTokenCleanup(authService)
    }

    launch {
        scheduleExpiredPendingSignupCleanup(authService)
    }

    launch {
        scheduleStaleDeviceTokenCleanup(authService)
    }

    launch {
        scheduleMediaUploadCleanup(mediaUploadService)
    }

    launch {
        scheduleMediaDraftCleanup(mediaDraftService)
    }

    launch {
        scheduleTrackingMaintenance(orderShippingService)
    }

    log.info("스케줄러가 시작되었습니다.")
}

/** 매일 새벽 3시 실행 */
private suspend fun Application.schedulePointExpiration(pointService: PointService) {
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 3)
            delay(delayUntilNext)

            log.info("[스케줄러] 포인트 만료 처리 시작: {}", Clock.System.now())
            val expiredCount = pointService.expirePoints()
            log.info("[스케줄러] 포인트 만료 처리 완료: {}건 처리됨", expiredCount)
        } catch (e: Exception) {
            log.error("[스케줄러] 포인트 만료 처리 중 오류 발생: {}", e.message, e)
            delay(1.hours)
        }
    }
}

/** 매일 새벽 3시 실행 */
private suspend fun Application.scheduleUserCouponExpiration(couponRedemptionService: CouponRedemptionService) {
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 3)
            delay(delayUntilNext)

            log.info("[스케줄러] 사용자 쿠폰 만료 처리 시작: {}", Clock.System.now())
            val expiredCount = couponRedemptionService.expireUserCoupons()
            log.info("[스케줄러] 사용자 쿠폰 만료 처리 완료: {}건 처리됨", expiredCount)
        } catch (e: Exception) {
            log.error("[스케줄러] 사용자 쿠폰 만료 처리 중 오류 발생: {}", e.message, e)
            delay(1.hours)
        }
    }
}

/** 15분마다 방치 주문·구독 결제 보정 */
private suspend fun Application.scheduleStalePendingPaymentReconcile(
    orderService: OrderService,
    subscriptionPaymentService: SubscriptionPaymentService
) {
    delay(2.minutes)
    while (true) {
        try {
            val processed = orderService.reconcileStalePendingOrders(olderThanMinutes = 30)
            if (processed > 0) {
                log.info("[스케줄러] 미결제 주문 정리 완료: {}건 처리", processed)
            }
        } catch (e: Exception) {
            log.error("[스케줄러] 미결제 주문 정리 중 오류 발생: {}", e.message, e)
        }
        try {
            val processed = subscriptionPaymentService.reconcileStalePendingPayments(olderThanMinutes = 30)
            if (processed > 0) {
                log.info("[스케줄러] 구독 결제 대기 정리 완료: {}건 처리", processed)
            }
        } catch (e: Exception) {
            log.error("[스케줄러] 구독 결제 대기 정리 중 오류 발생: {}", e.message, e)
        }
        delay(15.minutes)
    }
}

/** 매일 새벽 4시 실행 */
private suspend fun Application.scheduleSubscriptionExpiration(subscriptionService: SubscriptionService) {
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 4)
            delay(delayUntilNext)

            log.info("[스케줄러] 구독 만료 처리 시작: {}", Clock.System.now())
            val result = subscriptionService.expireSubscriptions()
            log.info("[스케줄러] 구독 만료 처리 완료: 성공 {}건, 실패 {}건", result.successCount, result.failCount)
        } catch (e: Exception) {
            log.error("[스케줄러] 구독 만료 처리 중 오류 발생: {}", e.message, e)
            delay(1.hours)
        }
    }
}

/** 1시간마다 실행 */
private suspend fun Application.scheduleSubscriptionRenewal(subscriptionService: SubscriptionService) {
    while (true) {
        try {
            log.info("[스케줄러] 구독 자동 갱신 처리 시작: {}", Clock.System.now())
            val result = subscriptionService.renewSubscriptions()
            log.info("[스케줄러] 구독 자동 갱신 처리 완료: 성공 {}건, 실패 {}건, 결과불명 {}건", result.successCount, result.failCount, result.unknownCount)
        } catch (e: Exception) {
            log.error("[스케줄러] 구독 자동 갱신 처리 중 오류 발생: {}", e.message, e)
        }

        delay(1.hours)
    }
}

/** 매일 새벽 5시 실행 */
private suspend fun Application.scheduleNotificationCleanup(notificationService: NotificationService) {
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 5)
            delay(delayUntilNext)

            log.info("[스케줄러] 오래된 알림 정리 시작: {}", Clock.System.now())
            val deactivatedCount = notificationService.cleanupOldNotifications(NOTIFICATION_RETENTION_DAYS)
            log.info("[스케줄러] 오래된 알림 정리 완료: {}건 비활성화", deactivatedCount)
        } catch (e: Exception) {
            log.error("[스케줄러] 오래된 알림 정리 중 오류 발생: {}", e.message, e)
            delay(1.hours)
        }
    }
}

/** 매일 새벽 2시 실행 */
private suspend fun Application.scheduleExpiredTokenCleanup(authService: AuthService) {
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 2)
            delay(delayUntilNext)

            log.info("[스케줄러] 만료 리프레시 토큰 정리 시작: {}", Clock.System.now())
            val deletedCount = authService.cleanupExpiredRefreshTokens()
            log.info("[스케줄러] 만료 리프레시 토큰 정리 완료: {}건 삭제", deletedCount)
        } catch (e: Exception) {
            log.error("[스케줄러] 만료 리프레시 토큰 정리 중 오류 발생: {}", e.message, e)
            delay(1.hours)
        }
    }
}

/** 매일 새벽 2시 실행 */
private suspend fun Application.scheduleExpiredPendingSignupCleanup(authService: AuthService) {
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 2)
            delay(delayUntilNext)

            log.info("[스케줄러] 만료 가입 대기 정리 시작: {}", Clock.System.now())
            val deletedCount = authService.cleanupExpiredPendingSignups(EXPIRED_PENDING_SIGNUP_RETENTION_DAYS)
            log.info("[스케줄러] 만료 가입 대기 정리 완료: {}건 삭제", deletedCount)
        } catch (e: Exception) {
            log.error("[스케줄러] 만료 가입 대기 정리 중 오류 발생: {}", e.message, e)
            delay(1.hours)
        }
    }
}

/** 매일 새벽 6시 실행 */
private suspend fun Application.scheduleStaleDeviceTokenCleanup(authService: AuthService) {
    // 알림 발송 후에 실행
    while (true) {
        try {
            val delayUntilNext = calcDelayUntilTime(hour = 6)
            delay(delayUntilNext)

            log.info("[스케줄러] 오래된 기기 등록 정리 시작: {}", Clock.System.now())
            val deletedCount = authService.cleanupStaleDeviceTokens(STALE_DEVICE_TOKEN_DAYS)
            log.info("[스케줄러] 오래된 기기 등록 정리 완료: {}건 삭제", deletedCount)
        } catch (e: Exception) {
            log.error("[스케줄러] 오래된 기기 등록 정리 중 오류 발생: {}", e.message, e)
            delay(1.hours)
        }
    }
}

/** 1시간마다 만료된 직접 업로드 정리 */
private suspend fun Application.scheduleMediaUploadCleanup(mediaUploadService: MediaUploadService) {
    // 첫 실행 전 잠시 대기
    delay(5.minutes)
    while (true) {
        try {
            val cleaned = mediaUploadService.cleanupExpired()
            if (cleaned > 0) {
                log.info("[스케줄러] 만료 업로드 정리 완료: {}건 처리", cleaned)
            }
        } catch (e: Exception) {
            log.error("[스케줄러] 만료 업로드 정리 중 오류 발생: {}", e.message, e)
        }
        delay(1.hours)
    }
}

/** 1시간마다 게시하지 않은 만료 초안 정리 */
private suspend fun Application.scheduleMediaDraftCleanup(mediaDraftService: MediaDraftService) {
    // 첫 실행 전 잠시 대기
    delay(5.minutes)
    while (true) {
        try {
            val cleaned = mediaDraftService.cleanupExpired()
            if (cleaned > 0) {
                log.info("[스케줄러] 만료 초안 정리 완료: {}건 처리", cleaned)
            }
        } catch (e: Exception) {
            log.error("[스케줄러] 만료 초안 정리 중 오류 발생: {}", e.message, e)
        }
        delay(1.hours)
    }
}

/** 1시간마다 배송추적 등록 실패분 재등록·웹훅 누락분 보정 */
private suspend fun Application.scheduleTrackingMaintenance(orderShippingService: OrderShippingService) {
    // 첫 실행 전 잠시 대기
    delay(10.minutes)
    while (true) {
        try {
            val registered = orderShippingService.retryMissingTrackingSubscriptions()
            if (registered > 0) {
                log.info("[스케줄러] 배송 추적 재등록 완료: {}건", registered)
            }
        } catch (e: Exception) {
            log.error("[스케줄러] 배송 추적 재등록 중 오류 발생: {}", e.message, e)
        }
        try {
            val applied = orderShippingService.reconcileTrackingSubscriptions()
            if (applied > 0) {
                log.info("[스케줄러] 배송 추적 결과 보정 완료: {}건", applied)
            }
        } catch (e: Exception) {
            log.error("[스케줄러] 배송 추적 결과 보정 중 오류 발생: {}", e.message, e)
        }
        delay(1.hours)
    }
}

private fun Application.calcDelayUntilTime(hour: Int): Long {
    val now = Clock.System.now()
    val nowLocal = now.toLocalDateTime(BUSINESS_TIMEZONE)

    val targetTime = LocalDateTime(
        year = nowLocal.year,
        month = nowLocal.month,
        day = nowLocal.day,
        hour = hour,
        minute = 0,
        second = 0,
        nanosecond = 0
    )

    var targetInstant = targetTime.toInstant(BUSINESS_TIMEZONE)

    if (now >= targetInstant) {
        targetInstant = targetInstant.plus(1.days)
    }

    val delayMillis = targetInstant.toEpochMilliseconds() - now.toEpochMilliseconds()

    log.info("[스케줄러] 다음 {}시({}) 실행 예정: {} ({}ms 후)", hour, BUSINESS_TIMEZONE.id, targetInstant.toLocalDateTime(BUSINESS_TIMEZONE), delayMillis)

    return delayMillis
}
