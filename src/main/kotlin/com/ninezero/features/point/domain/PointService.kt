package com.ninezero.features.point.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.PointType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidPointException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.point.PointDao
import com.ninezero.core.database.entities.point.PointHistoryDao
import com.ninezero.core.database.entities.point.PointHistoryTable
import com.ninezero.core.database.entities.point.PointTable
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.point.data.PointHistoryRepository
import com.ninezero.features.point.data.PointRepository
import com.ninezero.features.point.presentation.models.response.PointBalanceResponse
import com.ninezero.features.point.presentation.models.response.PointHistoryListResponse
import com.ninezero.features.point.toPointHistoryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days

class PointService(
    private val pointRepository: PointRepository,
    private val pointHistoryRepository: PointHistoryRepository,
    private val notificationService: NotificationService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    /** 가입과 같은 트랜잭션에서 계정 생성 */
    suspend fun createPointAccountInTransaction(userId: Int): PointDao =
        pointRepository.findByUserId(userId) ?: pointRepository.createPointAccount(userId)

    // 잔액
    suspend fun getBalance(userId: Int): PointBalanceResponse {
        return query {
            val point = pointRepository.findByUserId(userId)
                ?: throw NotFoundException(Errors.Point.POINT_ACCOUNT_NOT_FOUND)

            val now = nowUtc()
            val nextExpiring = pointHistoryRepository.findNextExpiring(userId, now)

            PointBalanceResponse(
                balance = point.balance.toAmountString(),
                totalEarned = point.totalEarned.toAmountString(),
                totalUsed = point.totalUsed.toAmountString(),
                totalExpired = point.totalExpired.toAmountString(),
                expiringPoints = nextExpiring?.amount?.toAmountString() ?: "0",
                expiresAt = nextExpiring?.expiresAt
            )
        }
    }

    // 내역
    suspend fun getHistory(
        userId: Int,
        page: Int,
        limit: Int,
        startDate: LocalDateTime? = null,
        endDate: LocalDateTime? = null,
        type: PointType? = null
    ): PointHistoryListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)
        // 업무 시간대 날짜를 UTC 조회 구간으로 변환
        val rangeStart = startDate?.date?.startOfBusinessDayUtc()
        val rangeEnd = endDate?.date?.plus(DatePeriod(days = 1))?.startOfBusinessDayUtc()

        return query {
            val histories = pointHistoryRepository.findByUserId(userId, validPage, validLimit, rangeStart, rangeEnd, type)
            val totalCount = pointHistoryRepository.countByUserId(userId, rangeStart, rangeEnd, type)

            val pagination = PaginationInfo(
                page = validPage,
                limit = validLimit,
                totalCount = totalCount.toInt()
            )

            createPagedResponse(
                items = histories.map { it.toPointHistoryResponse() },
                pagination = pagination
            )
        }
    }

    // 적립
    fun earnPointsInTransaction(
        userId: Int,
        amount: BigDecimal,
        orderId: Int,
        description: String
    ): BigDecimal {
        ValidationUtils.validatePointAmount(amount)
        ValidationUtils.validatePointReason(description)

        // 같은 유저의 동시 적립을 직렬화한 뒤 멱등 판정
        val point = PointDao.find { PointTable.userId eq userId }.forUpdate().singleOrNull()
            ?.also { it.refresh(flush = false) }
            ?: PointDao.new { this.userId = userId }

        // 멱등 가드
        val alreadyEarned = PointHistoryDao.find { PointHistoryTable.orderId eq orderId }
            .any { it.type == PointType.EARN }
        if (alreadyEarned) return BigDecimal.ZERO

        val balanceBefore = point.balance
        val balanceAfter = balanceBefore.add(amount)
        val expiresAt = AppClock.clock.now()
            .plus(Constants.Point.EXPIRATION_DAYS.days)
            .toLocalDateTime(TimeZone.UTC)

        point.balance = balanceAfter
        point.totalEarned = point.totalEarned.add(amount)

        PointHistoryDao.new {
            this.userId = userId
            this.type = PointType.EARN
            this.amount = amount
            this.balanceBefore = balanceBefore
            this.balanceAfter = balanceAfter
            this.orderId = orderId
            this.description = description
            this.expiresAt = expiresAt
        }

        return amount
    }

    // 사용
    fun usePointsInTransaction(
        userId: Int,
        amount: BigDecimal,
        orderId: Int,
        orderAmount: BigDecimal
    ) {
        ValidationUtils.validatePointAmount(amount)

        val point = PointDao.find { PointTable.userId eq userId }.forUpdate().singleOrNull()
            ?.also { it.refresh(flush = false) }
            ?: throw NotFoundException(Errors.Point.POINT_ACCOUNT_NOT_FOUND)

        pointAmountError(amount, point.balance, orderAmount)?.let { throw InvalidPointException(it) }

        val balanceBefore = point.balance
        val balanceAfter = balanceBefore.subtract(amount)

        point.balance = balanceAfter
        point.totalUsed = point.totalUsed.add(amount)

        PointHistoryDao.new {
            this.userId = userId
            this.type = PointType.USE
            this.amount = amount
            this.balanceBefore = balanceBefore
            this.balanceAfter = balanceAfter
            this.orderId = orderId
            this.description = "주문 결제 시 포인트 사용"
        }
    }

    // 환불
    fun refundPointsInTransaction(
        userId: Int,
        amount: BigDecimal,
        orderId: Int
    ) {
        ValidationUtils.validatePointAmount(amount)

        val point = PointDao.find { PointTable.userId eq userId }.forUpdate().singleOrNull()
            ?.also { it.refresh(flush = false) }
            ?: throw NotFoundException(Errors.Point.POINT_ACCOUNT_NOT_FOUND)

        val balanceBefore = point.balance
        val balanceAfter = balanceBefore.add(amount)

        point.balance = balanceAfter

        PointHistoryDao.new {
            this.userId = userId
            this.type = PointType.REFUND
            this.amount = amount
            this.balanceBefore = balanceBefore
            this.balanceAfter = balanceAfter
            this.orderId = orderId
            this.description = "주문 취소로 인한 포인트 환불"
        }
    }

    // 만료
    suspend fun expirePoints(): Int {
        val now = nowUtc()
        val batchSize = 100
        var expiredCount = 0
        val processedNotifications = mutableListOf<Pair<Int, BigDecimal>>()

        while (true) {
            val batch = query {
                pointHistoryRepository.findExpiredPoints(now, batchSize).map { it.id.value }
            }

            if (batch.isEmpty()) break

            var clearedCount = 0
            for (historyId in batch) {
                // 하나가 실패해도 나머지가 막히지 않게 내역마다 트랜잭션 분리
                val expired = try {
                    query { expireHistory(historyId) }
                } catch (e: Exception) {
                    logger.error("포인트 만료 처리 실패 - historyId: $historyId, error: ${e.message}", e)
                    continue
                }

                clearedCount++
                expired?.let {
                    processedNotifications.add(it)
                    expiredCount++
                }
            }

            // 처리 못 한 내역만 남으면 중단
            if (clearedCount == 0) {
                logger.error("포인트 만료 처리 중단 - 처리 못 한 내역 ${batch.size}건")
                break
            }
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            // 분 단위 중복 제거에 합쳐지지 않게 사용자별 합산
            val expiredByUser = processedNotifications
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, amounts) -> amounts.fold(BigDecimal.ZERO, BigDecimal::add) }
            expiredByUser.forEach { (userId, amount) ->
                try {
                    notificationService.sendPointExpiredNotification(
                        userId = userId,
                        amount = amount
                    )
                } catch (e: Exception) {
                    logger.error("포인트 만료 알림 전송 실패 - userId: $userId, error: ${e.message}", e)
                }
            }
        }

        return expiredCount
    }

    /** 만료 내역 차감 결과, 차감했을 때만 사용자와 금액 */
    private fun expireHistory(historyId: Int): Pair<Int, BigDecimal>? {
        // 겹친 만료 처리가 이미 차감한 내역은 건너뜀
        val history = PointHistoryDao.find { PointHistoryTable.id eq historyId }.forUpdate().singleOrNull()
            ?.also { it.refresh(flush = false) } ?: return null
        if (history.expiresAt == null) return null

        // 사용·환불과 같은 잠금으로 동시 변경 유실 방지
        val point = PointDao.find { PointTable.userId eq history.userId }.forUpdate().singleOrNull()
            ?.also { it.refresh(flush = false) }
        if (point == null) {
            // 잔액 없으면 만료일만 비움
            history.expiresAt = null
            return null
        }

        val balanceBefore = point.balance
        val balanceAfter = balanceBefore.subtract(history.amount)

        // 음수 되면 차감 안 함
        if (balanceAfter < BigDecimal.ZERO) {
            history.expiresAt = null
            return null
        }

        point.balance = balanceAfter
        point.totalExpired = point.totalExpired.add(history.amount)

        // 기존 EARN 내역의 만료일 null로 변경
        history.expiresAt = null

        PointHistoryDao.new {
            this.userId = history.userId
            this.type = PointType.EXPIRE
            this.amount = history.amount
            this.balanceBefore = balanceBefore
            this.balanceAfter = balanceAfter
            this.description = "포인트 유효기간 만료"
        }

        return history.userId to history.amount
    }
}
