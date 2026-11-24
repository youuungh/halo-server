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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
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
private suspend fun Application.schedulePointExpiration(pointService: PointService) = repeatDaily(hour = 3) {
    runLogged("포인트 만료 처리") {
        log.info("[스케줄러] 포인트 만료 처리 시작: {}", Clock.System.now())
        val expiredCount = pointService.expirePoints()
        log.info("[스케줄러] 포인트 만료 처리 완료: {}건 처리됨", expiredCount)
    }
}

/** 매일 새벽 3시 실행 */
private suspend fun Application.scheduleUserCouponExpiration(couponRedemptionService: CouponRedemptionService) = repeatDaily(hour = 3) {
    runLogged("사용자 쿠폰 만료 처리") {
        log.info("[스케줄러] 사용자 쿠폰 만료 처리 시작: {}", Clock.System.now())
        val expiredCount = couponRedemptionService.expireUserCoupons()
        log.info("[스케줄러] 사용자 쿠폰 만료 처리 완료: {}건 처리됨", expiredCount)
    }
}

/** 15분마다 방치 주문·구독 결제 보정 */
private suspend fun Application.scheduleStalePendingPaymentReconcile(
    orderService: OrderService,
    subscriptionPaymentService: SubscriptionPaymentService
) = repeatEvery(15.minutes, initialDelay = 2.minutes) {
    runLogged("미결제 주문 정리") {
        val processed = orderService.reconcileStalePendingOrders(olderThanMinutes = 30)
        if (processed > 0) {
            log.info("[스케줄러] 미결제 주문 정리 완료: {}건 처리", processed)
        }
    }
    runLogged("구독 결제 대기 정리") {
        val processed = subscriptionPaymentService.reconcileStalePendingPayments(olderThanMinutes = 30)
        if (processed > 0) {
            log.info("[스케줄러] 구독 결제 대기 정리 완료: {}건 처리", processed)
        }
    }
}

/** 매일 새벽 4시 실행 */
private suspend fun Application.scheduleSubscriptionExpiration(subscriptionService: SubscriptionService) = repeatDaily(hour = 4) {
    runLogged("구독 만료 처리") {
        log.info("[스케줄러] 구독 만료 처리 시작: {}", Clock.System.now())
        val result = subscriptionService.expireSubscriptions()
        log.info("[스케줄러] 구독 만료 처리 완료: 성공 {}건, 실패 {}건", result.successCount, result.failCount)
    }
}

/** 1시간마다 실행 */
private suspend fun Application.scheduleSubscriptionRenewal(subscriptionService: SubscriptionService) = repeatEvery(1.hours) {
    runLogged("구독 자동 갱신 처리") {
        log.info("[스케줄러] 구독 자동 갱신 처리 시작: {}", Clock.System.now())
        val result = subscriptionService.renewSubscriptions()
        log.info("[스케줄러] 구독 자동 갱신 처리 완료: 성공 {}건, 실패 {}건, 결과불명 {}건", result.successCount, result.failCount, result.unknownCount)
    }
}

/** 매일 새벽 5시 실행 */
private suspend fun Application.scheduleNotificationCleanup(notificationService: NotificationService) = repeatDaily(hour = 5) {
    runLogged("오래된 알림 정리") {
        log.info("[스케줄러] 오래된 알림 정리 시작: {}", Clock.System.now())
        val deactivatedCount = notificationService.cleanupOldNotifications(NOTIFICATION_RETENTION_DAYS)
        log.info("[스케줄러] 오래된 알림 정리 완료: {}건 비활성화", deactivatedCount)
    }
}

/** 매일 새벽 2시 실행 */
private suspend fun Application.scheduleExpiredTokenCleanup(authService: AuthService) = repeatDaily(hour = 2) {
    runLogged("만료 리프레시 토큰 정리") {
        log.info("[스케줄러] 만료 리프레시 토큰 정리 시작: {}", Clock.System.now())
        val deletedCount = authService.cleanupExpiredRefreshTokens()
        log.info("[스케줄러] 만료 리프레시 토큰 정리 완료: {}건 삭제", deletedCount)
    }
}

/** 매일 새벽 2시 실행 */
private suspend fun Application.scheduleExpiredPendingSignupCleanup(authService: AuthService) = repeatDaily(hour = 2) {
    runLogged("만료 가입 대기 정리") {
        log.info("[스케줄러] 만료 가입 대기 정리 시작: {}", Clock.System.now())
        val deletedCount = authService.cleanupExpiredPendingSignups(EXPIRED_PENDING_SIGNUP_RETENTION_DAYS)
        log.info("[스케줄러] 만료 가입 대기 정리 완료: {}건 삭제", deletedCount)
    }
}

/** 매일 새벽 6시 실행, 알림 발송 후 */
private suspend fun Application.scheduleStaleDeviceTokenCleanup(authService: AuthService) = repeatDaily(hour = 6) {
    runLogged("오래된 기기 등록 정리") {
        log.info("[스케줄러] 오래된 기기 등록 정리 시작: {}", Clock.System.now())
        val deletedCount = authService.cleanupStaleDeviceTokens(STALE_DEVICE_TOKEN_DAYS)
        log.info("[스케줄러] 오래된 기기 등록 정리 완료: {}건 삭제", deletedCount)
    }
}

/** 1시간마다 만료된 직접 업로드 정리 */
private suspend fun Application.scheduleMediaUploadCleanup(mediaUploadService: MediaUploadService) = repeatEvery(1.hours, initialDelay = 5.minutes) {
    runLogged("만료 업로드 정리") {
        val cleaned = mediaUploadService.cleanupExpired()
        if (cleaned > 0) {
            log.info("[스케줄러] 만료 업로드 정리 완료: {}건 처리", cleaned)
        }
    }
}

/** 1시간마다 게시하지 않은 만료 초안 정리 */
private suspend fun Application.scheduleMediaDraftCleanup(mediaDraftService: MediaDraftService) = repeatEvery(1.hours, initialDelay = 5.minutes) {
    runLogged("만료 초안 정리") {
        val cleaned = mediaDraftService.cleanupExpired()
        if (cleaned > 0) {
            log.info("[스케줄러] 만료 초안 정리 완료: {}건 처리", cleaned)
        }
    }
}

/** 1시간마다 배송추적 등록 실패분 재등록·웹훅 누락분 보정 */
private suspend fun Application.scheduleTrackingMaintenance(orderShippingService: OrderShippingService) = repeatEvery(1.hours, initialDelay = 10.minutes) {
    runLogged("배송 추적 재등록") {
        val registered = orderShippingService.retryMissingTrackingSubscriptions()
        if (registered > 0) {
            log.info("[스케줄러] 배송 추적 재등록 완료: {}건", registered)
        }
    }
    runLogged("배송 추적 결과 보정") {
        val applied = orderShippingService.reconcileTrackingSubscriptions()
        if (applied > 0) {
            log.info("[스케줄러] 배송 추적 결과 보정 완료: {}건", applied)
        }
    }
}

/** 매일 [hour]시(BUSINESS_TIMEZONE)마다 실행 */
private suspend fun Application.repeatDaily(hour: Int, block: suspend () -> Unit) {
    while (true) {
        delay(calcDelayUntilTime(hour))
        block()
    }
}

/** [initialDelay] 뒤 [interval]마다 실행 */
private suspend fun repeatEvery(interval: Duration, initialDelay: Duration = Duration.ZERO, block: suspend () -> Unit) {
    delay(initialDelay)
    while (true) {
        block()
        delay(interval)
    }
}

/** 실패는 기록만 하고 다음 차례로, 서버 종료 취소는 그대로 전달 */
private suspend fun Application.runLogged(name: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("[스케줄러] {} 중 오류 발생: {}", name, e.message, e)
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
