package com.ninezero.core.common.util

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import com.ninezero.core.common.config.SubscriptionPlanTier
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock

// JSON 변환 유틸리티

fun List<String>.encodeToJson(): String {
    return if (this.isEmpty()) "" else Json.encodeToString(this)
}

fun String?.decodeJsonToList(): List<String> {
    if (this.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<String>>(this)
    } catch (_: Exception) {
        // 기존 쉼표 구분 방식과의 호환성
        this.split(",").filter { it.isNotBlank() }
    }
}

// 해시태그 관련 유틸리티

fun extractHashtags(content: String): List<String> {
    val hashtagRegex = "#[\\w가-힣]+".toRegex()
    return hashtagRegex.findAll(content)
        .map { it.value.substring(1) } // # 제거
        .distinct()
        .filter { isValidHashtag(it) }
        .take(Constants.Social.MAX_HASHTAGS_PER_POST)
        .toList()
}

fun isValidHashtag(hashtag: String): Boolean {
    return hashtag.length in Constants.Social.MIN_HASHTAG_LENGTH..Constants.Social.MAX_HASHTAG_LENGTH &&
            hashtag.matches("[\\w가-힣]+".toRegex())
}

// 미디어 URL 처리 유틸리티

fun List<String>.encodeToMediaAttachments(): String? {
    return if (this.isNotEmpty()) {
        try {
            Json.encodeToString(this)
        } catch (_: Exception) {
            // 실패 시 쉼표 구분 폴백
            this.joinToString(",")
        }
    } else null
}

fun String?.decodeToMediaAttachments(): List<String> {
    if (this.isNullOrBlank()) return emptyList()

    return try {
        Json.decodeFromString<List<String>>(this)
    } catch (_: Exception) {
        // 기존 쉼표 구분 방식과의 호환성
        this.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}

fun List<String>.validateProductImages(): List<String> {
    return this
        .filter { it.isNotBlank() }
        .distinct()
        .take(Constants.Commerce.MAX_PRODUCT_IMAGES)
}

// 포스트 관련 유틸리티

/** @username 멘션 추출 */
fun extractMentions(content: String): List<String> {
    val mentionRegex = "@([\\w가-힣]{2,50})".toRegex()
    return mentionRegex.findAll(content)
        .map { it.groupValues[1] }  // @ 제거하고 username만
        .distinct()
        .toList()
}

// 채팅 메시지 관련 유틸리티

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

// 날짜/시간 유틸리티

fun nowUtc(): LocalDateTime {
    return Clock.System.now().toLocalDateTime(TimeZone.UTC)
}

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

/** 통계용 오늘/이번주/이번달 UTC 경계 */
data class StatisticsDateBoundaries(
    val todayStart: LocalDateTime,
    val todayEnd: LocalDateTime,
    val weekStart: LocalDateTime,
    val monthStart: LocalDateTime
)

fun statisticsDateBoundaries(): StatisticsDateBoundaries {
    val today = nowUtc()

    val todayStart = LocalDateTime(today.year, today.month.number, today.day, 0, 0, 0)
    val todayEnd = LocalDateTime(today.year, today.month.number, today.day, 23, 59, 59)

    val weekStart = todayStart.date.minus(DatePeriod(days = 7))
        .atTime(0, 0, 0)

    val monthStart = LocalDateTime(today.year, today.month.number, 1, 0, 0, 0)

    return StatisticsDateBoundaries(todayStart, todayEnd, weekStart, monthStart)
}

// 포인트 관련 유틸리티

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

fun calcMaxUsablePoints(orderAmount: BigDecimal): BigDecimal {
    return orderAmount
        .multiply(Constants.Point.MAX_USE_RATE_DECIMAL)  // 주문 금액의 50% 상한
        .setScale(0, RoundingMode.HALF_UP)
}

fun isValidPointAmount(
    pointsToUse: BigDecimal,
    currentBalance: BigDecimal,
    orderAmount: BigDecimal
): Pair<Boolean, String?> {
    if (currentBalance < pointsToUse) {
        return false to Errors.Point.INSUFFICIENT_POINTS
    }

    if (pointsToUse < Constants.Point.MIN_USE_AMOUNT_DECIMAL) {
        return false to "최소 ${Constants.Point.MIN_USE_AMOUNT_DECIMAL.toInt()}원 이상부터 사용 가능합니다."
    }

    val maxUsable = calcMaxUsablePoints(orderAmount)
    if (pointsToUse > maxUsable) {
        return false to "최대 ${maxUsable}원까지 사용할 수 있습니다. (주문 금액의 50%)"
    }

    return true to null
}

// 쿠폰 관련 유틸리티

fun List<Int>.encodeToTargetIds(): String? {
    return if (this.isEmpty()) null else Json.encodeToString(this)
}

fun String?.decodeToTargetIds(): List<Int> {
    if (this.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<Int>>(this)
    } catch (_: Exception) {
        emptyList()
    }
}
