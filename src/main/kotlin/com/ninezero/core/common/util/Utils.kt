package com.ninezero.core.common.util

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.exception.Errors
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Instant

// JSON 변환

fun List<String>.encodeToJson(): String {
    return if (this.isEmpty()) "" else Json.encodeToString(this)
}

fun String?.decodeJsonToList(): List<String> {
    if (this.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<String>>(this)
    } catch (_: Exception) {
        emptyList()
    }
}

// 채팅 메시지 미리보기

fun createMessagePreview(
    content: String?,
    messageType: ChatMessageType,
    mediaAttachments: List<String>?
): String {
    return when {
        !content.isNullOrBlank() -> content.take(50)
        messageType == ChatMessageType.IMAGE -> {
            val count = mediaAttachments?.size ?: 1
            if (count > 1) "[이미지 ${count}장]" else "[이미지]"
        }
        messageType == ChatMessageType.VIDEO -> {
            val count = mediaAttachments?.size ?: 1
            if (count > 1) "[비디오 ${count}개]" else "[비디오]"
        }
        messageType == ChatMessageType.FILE -> {
            val count = mediaAttachments?.size ?: 1
            if (count > 1) "[파일 ${count}개]" else "[파일]"
        }
        messageType == ChatMessageType.PRODUCT_LINK -> "[상품]"
        messageType == ChatMessageType.POST_LINK -> "[포스트]"
        else -> "[메시지]"
    }
}

// 날짜·시간

object AppClock {
    @Volatile
    var clock: Clock = Clock.System
}

fun nowUtc(): LocalDateTime {
    return AppClock.clock.now().toLocalDateTime(TimeZone.UTC)
}

/** 달·날짜 경계 기준 업무 시간대 */
val BUSINESS_TIMEZONE: TimeZone by lazy {
    TimeZone.of(DotenvConfig.getOrDefault("SCHEDULER_TIMEZONE", "Asia/Seoul"))
}

/** 업무 시간대 날짜 0시의 저장용 UTC 시각 */
fun LocalDate.startOfBusinessDayUtc(): LocalDateTime =
    atStartOfDayIn(BUSINESS_TIMEZONE).toLocalDateTime(TimeZone.UTC)

fun calcDaysBetween(
    from: LocalDateTime,
    to: LocalDateTime
): Int {
    val fromInstant = from.toInstant(TimeZone.UTC)
    val toInstant = to.toInstant(TimeZone.UTC)
    val diff = toInstant - fromInstant
    return diff.inWholeDays.toInt()
}

fun calcDaysRemaining(
    expiresAt: LocalDateTime,
    now: LocalDateTime = nowUtc()
): Int {
    val days = calcDaysBetween(now, expiresAt)
    return if (days < 0) 0 else days  // 음수면 0 반환
}

/** 업무 시간대 기준 통계 날짜 경계 */
data class StatisticsDateBoundaries(
    val todayStart: LocalDateTime,
    val tomorrowStart: LocalDateTime,
    val weekStart: LocalDateTime,
    val monthStart: LocalDateTime
)

fun statisticsDateBoundaries(now: Instant = Clock.System.now()): StatisticsDateBoundaries {
    val today = now.toLocalDateTime(BUSINESS_TIMEZONE).date
    return StatisticsDateBoundaries(
        todayStart = today.startOfBusinessDayUtc(),
        tomorrowStart = today.plus(DatePeriod(days = 1)).startOfBusinessDayUtc(),
        weekStart = today.minus(DatePeriod(days = 6)).startOfBusinessDayUtc(),
        monthStart = LocalDate(today.year, today.month, 1).startOfBusinessDayUtc()
    )
}

// 포인트 적립·사용

fun getEarnRateForTier(tier: SubscriptionPlanTier): BigDecimal {
    return when (tier) {
        SubscriptionPlanTier.FREE -> Constants.Point.EARN_RATE_DECIMAL
        SubscriptionPlanTier.TIER1 -> Constants.Point.TIER1_EARN_RATE_DECIMAL
        SubscriptionPlanTier.TIER2 -> Constants.Point.TIER2_EARN_RATE_DECIMAL
    }
}

fun calcEarnByTier(orderAmount: BigDecimal, tier: SubscriptionPlanTier): BigDecimal {
    return orderAmount.multiply(getEarnRateForTier(tier)).setScale(0, RoundingMode.HALF_UP)
}

private fun calcMaxUsablePoints(orderAmount: BigDecimal): BigDecimal {
    return orderAmount
        .multiply(Constants.Point.MAX_USE_RATE_DECIMAL)  // 주문 금액의 50% 상한
        .setScale(0, RoundingMode.HALF_UP)
}

fun pointAmountError(
    pointsToUse: BigDecimal,
    currentBalance: BigDecimal,
    orderAmount: BigDecimal
): String? {
    if (currentBalance < pointsToUse) {
        return Errors.Point.INSUFFICIENT_POINTS
    }

    if (pointsToUse < Constants.Point.MIN_USE_AMOUNT_DECIMAL) {
        return "최소 ${Constants.Point.MIN_USE_AMOUNT_DECIMAL.toInt()}원 이상부터 사용 가능합니다."
    }

    val maxUsable = calcMaxUsablePoints(orderAmount)
    if (pointsToUse > maxUsable) {
        return "최대 ${maxUsable}원까지 사용할 수 있습니다. (주문 금액의 50%)"
    }

    return null
}
