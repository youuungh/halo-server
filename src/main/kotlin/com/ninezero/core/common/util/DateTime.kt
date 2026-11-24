package com.ninezero.core.common.util

import com.ninezero.core.common.config.DotenvConfig
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

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
