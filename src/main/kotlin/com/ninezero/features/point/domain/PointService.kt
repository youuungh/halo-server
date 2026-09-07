package com.ninezero.features.point.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.PointType
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
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
import com.ninezero.features.point.toHistoryResponse
import io.ktor.server.plugins.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class PointService(
    private val pointRepository: PointRepository,
    private val pointHistoryRepository: PointHistoryRepository,
    private val notificationService: NotificationService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    // 계정·잔액·내역
    suspend fun createPointAccount(userId: Int) {
        val alreadyExists = query {
            val existing = pointRepository.findByUserId(userId)
            if (existing != null) {
                return@query true
            }

            pointRepository.createPointAccount(userId)
            false
        }

        if (alreadyExists) {
            throw ConflictException(Messages.Point.POINT_ACCOUNT_EXISTS)
        }
    }

    suspend fun getBalance(userId: Int): PointBalanceResponse {
        return query {
            val point = pointRepository.findByUserId(userId)
                ?: throw NotFoundException(Errors.Point.POINT_ACCOUNT_NOT_FOUND)

            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
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

    suspend fun getHistory(
        userId: Int,
        page: Int,
        limit: Int,
        startDate: LocalDateTime? = null,
        endDate: LocalDateTime? = null,
        type: PointType? = null
    ): PointHistoryListResponse {
        return query {
            val histories = pointHistoryRepository.findByUserId(userId, page, limit, startDate, endDate, type)
            val totalCount = pointHistoryRepository.countByUserId(userId, startDate, endDate, type)

            val pagination = PaginationInfo(
                page = page,
                limit = limit,
                totalCount = totalCount.toInt()
            )

            createPagedResponse(
                items = histories.map { it.toHistoryResponse() },
                pagination = pagination
            )
        }
    }

    // 적립
    suspend fun earnPoints(
        userId: Int,
        amount: BigDecimal,
        orderId: Int,
        description: String
    ) {
        ValidationUtils.validatePointAmount(amount)
        ValidationUtils.validatePointReason(description)

        query {
            // 멱등 가드
            val alreadyEarned = PointHistoryDao.find { PointHistoryTable.orderId eq orderId }
                .any { it.type == PointType.EARN }
            if (alreadyEarned) return@query

            var point = PointDao.find { PointTable.userId eq userId }.singleOrNull()

            if (point == null) {
                point = PointDao.new {
                    this.userId = userId
                    this.balance = BigDecimal.ZERO
                    this.totalEarned = BigDecimal.ZERO
                    this.totalUsed = BigDecimal.ZERO
                    this.totalExpired = BigDecimal.ZERO
                }
            }

            val balanceBefore = point.balance
            val balanceAfter = balanceBefore.add(amount)
            val expiresAt = Clock.System.now()
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
        }
    }

    // 사용
    suspend fun usePoints(
        userId: Int,
        amount: BigDecimal,
        orderId: Int,
        orderAmount: BigDecimal
    ) {
        query {
            usePointsInTransaction(userId, amount, orderId, orderAmount)
        }
    }

    fun usePointsInTransaction(
        userId: Int,
        amount: BigDecimal,
        orderId: Int,
        orderAmount: BigDecimal
    ) {
        ValidationUtils.validatePointAmount(amount)

        val point = PointDao.find { PointTable.userId eq userId }.singleOrNull()
            ?: throw NotFoundException(Errors.Point.POINT_ACCOUNT_NOT_FOUND)

        val (isValid, errorMessage) = isValidPointAmount(amount, point.balance, orderAmount)
        if (!isValid) {
            throw BadRequestException(errorMessage!!)
        }

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
    suspend fun refundPoints(
        userId: Int,
        amount: BigDecimal,
        orderId: Int
    ) {
        query {
            refundPointsInTransaction(userId, amount, orderId)
        }
    }

    fun refundPointsInTransaction(
        userId: Int,
        amount: BigDecimal,
        orderId: Int
    ) {
        ValidationUtils.validatePointAmount(amount)

        val point = PointDao.find { PointTable.userId eq userId }.singleOrNull()
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
                pointHistoryRepository.findExpiredPoints(now, batchSize)
            }

            if (batch.isEmpty()) break

            query {
                for (history in batch) {
                    try {
                        val point = PointDao.find { PointTable.userId eq history.userId }.singleOrNull()
                        if (point == null) {
                            // 잔액 없으면 만료일만 비움
                            history.expiresAt = null
                            continue
                        }

                        val balanceBefore = point.balance
                        val balanceAfter = balanceBefore.subtract(history.amount)

                        // 음수 되면 차감 안 함
                        if (balanceAfter < BigDecimal.ZERO) {
                            history.expiresAt = null
                            continue
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

                        processedNotifications.add(history.userId to history.amount)
                        expiredCount++
                    } catch (e: Exception) {
                        logger.error("포인트 만료 처리 실패 - userId: ${history.userId}, error: ${e.message}", e)
                    }
                }
            }
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            processedNotifications.forEach { (userId, amount) ->
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
}
