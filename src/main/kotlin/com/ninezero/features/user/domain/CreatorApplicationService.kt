package com.ninezero.features.user.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CreatorApplicationStatus
import com.ninezero.core.common.config.CreatorOrderFilter
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import com.ninezero.features.commerce.data.OrderRepository
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.coupon.data.CouponRepository
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.CreatorApplicationRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.request.CreatorApplicationRequest
import com.ninezero.features.user.presentation.models.request.RejectApplicationRequest
import com.ninezero.features.user.presentation.models.response.CreatorApplicationListResponse
import com.ninezero.features.user.presentation.models.response.CreatorApplicationResponse
import com.ninezero.features.user.presentation.models.response.CreatorStatisticsResponse
import com.ninezero.features.user.toCreatorApplicationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.util.Locale
import kotlin.time.Clock

class CreatorApplicationService(
    private val applicationRepository: CreatorApplicationRepository,
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val orderRepository: OrderRepository,
    private val subscriptionPlanRepository: SubscriptionPlanRepository,
    private val productRepository: ProductRepository,
    private val postRepository: PostRepository,
    private val couponRepository: CouponRepository,
    private val notificationService: NotificationService,
    private val coroutineScope: CoroutineScope,
    private val cacheService: CacheService
) {
    private val logger = logger()

    private data class CreatorTeardownResult(
        val productIds: List<Int>,
        val postIds: List<Int>
    )

    // 신청
    suspend fun applyForCreator(
        userId: Int,
        request: CreatorApplicationRequest
    ): CreatorApplicationResponse {
        val user = query {
            userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)
        }

        when (user.role) {
            UserRole.CREATOR -> throw ValidationException(Errors.User.ALREADY_CREATOR)
            UserRole.ADMIN -> throw ValidationException(Errors.User.ADMIN_CANNOT_BE_CREATOR)
            UserRole.USER -> Unit  // USER만 신청 가능
        }

        val lastClosed = query {
            applicationRepository.findLastClosedByUserId(userId)
        }

        val reviewedAt = lastClosed?.reviewedAt
        if (reviewedAt != null) {
            val daysDiff = (Clock.System.now() - reviewedAt.toInstant(TimeZone.UTC)).inWholeDays

            if (daysDiff < Constants.User.CREATOR_REAPPLY_COOLDOWN_DAYS) {
                val remainingDays = Constants.User.CREATOR_REAPPLY_COOLDOWN_DAYS - daysDiff
                throw ValidationException(
                    "거절 후 ${Constants.User.CREATOR_REAPPLY_COOLDOWN_DAYS}일 이후에 재신청 가능합니다. (${remainingDays}일 남음)"
                )
            }
        }

        val reason = request.reason.trim()
        val portfolioUrl = request.portfolioUrl?.let { ValidationUtils.sanitizeUrl(it) }

        ValidationUtils.validateCreatorApplicationReason(reason)

        val application = query {
            // 같은 유저의 동시 신청 직렬화 후 PENDING 재검사
            UserDao.find { UserTable.id eq userId }.forUpdate().singleOrNull()
                ?: throw UserNotFoundException(userId)

            if (applicationRepository.findByUserId(userId)?.status == CreatorApplicationStatus.PENDING) {
                throw ConflictException(Errors.User.PENDING_APPLICATION_EXISTS)
            }

            applicationRepository.createApplication(
                userId = userId,
                reason = reason,
                portfolioUrl = portfolioUrl
            )
        }

        return application.toCreatorApplicationResponse(user.username)
    }

    suspend fun getMyApplication(userId: Int): CreatorApplicationResponse {
        val (user, application) = query {
            val foundUser = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            val foundApp = applicationRepository.findByUserId(userId)
                ?: throw NotFoundException(Errors.User.NO_APPLICATION_FOUND)

            foundUser to foundApp
        }

        return application.toCreatorApplicationResponse(user.username)
    }

    suspend fun getApplications(
        status: CreatorApplicationStatus?,
        page: Int,
        limit: Int,
        search: String? = null,
        sortBy: String? = null,
        sortOrder: String? = null
    ): CreatorApplicationListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        val (applications, total) = query {
            val apps = applicationRepository.findApplicationsByStatus(status, validPage, validLimit, search, sortBy, sortOrder)
            val count = applicationRepository.countByStatus(status, search)

            apps to count
        }

        val userIds = applications.map { it.userId }.distinct()
        // 프로필 참조는 트랜잭션 안에서 읽는다
        val responses = query {
            val users = userRepository.findUsersByIds(userIds).associateBy { it.id.value }

            applications.map { app ->
                val user = users[app.userId]
                app.toCreatorApplicationResponse(
                    username = user?.username ?: "Unknown",
                    displayName = user?.profile?.displayName,
                    avatarUrl = user?.profile?.avatarUrl
                )
            }
        }

        val pagination = PaginationInfo(validPage, validLimit, total)
        return createPagedResponse(responses, pagination)
    }

    // 승인/거절
    suspend fun approveApplication(
        applicationId: Int,
        adminId: Int
    ): String {
        val (applicantUserId, restoredPostIds) = query {
            val userId = applicationRepository.approveApplication(applicationId, adminId)
            userRepository.promoteToCreator(userId)
            // HIDDEN 마커 글만 복구
            val restored = postRepository.restoreCreatorHiddenPosts(userId)
            userId to restored
        }

        invalidateCreatorLifecycleCaches(applicantUserId, emptyList(), restoredPostIds)

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendCreatorApprovedNotification(
                    userId = applicantUserId,
                    applicationId = applicationId
                )
            } catch (e: Exception) {
                logger.warn("알림 발송 실패: {}", e.message)
            }
        }

        return Messages.User.APPLICATION_APPROVED
    }

    suspend fun rejectApplication(
        applicationId: Int,
        adminId: Int,
        request: RejectApplicationRequest
    ): String {
        val rejectionReason = request.rejectionReason.trim()
        ValidationUtils.validateRejectionReason(rejectionReason)

        val applicantUserId = query {
            applicationRepository.rejectApplication(applicationId, adminId, rejectionReason)
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendCreatorRejectedNotification(
                    userId = applicantUserId,
                    applicationId = applicationId,
                    rejectionReason = rejectionReason
                )
            } catch (e: Exception) {
                logger.warn("알림 발송 실패: {}", e.message)
            }
        }

        return Messages.User.APPLICATION_REJECTED
    }

    // 강등/셀프 해제
    suspend fun demoteCreator(userId: Int, reason: String, adminId: Int): String {
        val trimmedReason = reason.trim()
        ValidationUtils.validateRejectionReason(trimmedReason)

        val result = teardownCreatorAssets(
            userId = userId,
            reason = trimmedReason,
            reviewerId = adminId
        )

        invalidateCreatorLifecycleCaches(userId, result.productIds, result.postIds)

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendCreatorDemotedNotification(userId, trimmedReason)
            } catch (e: Exception) {
                logger.warn("알림 발송 실패: {}", e.message)
            }
        }

        return Messages.User.CREATOR_DEMOTED
    }

    suspend fun revokeSelf(userId: Int): String {
        val result = teardownCreatorAssets(
            userId = userId,
            reason = Messages.User.CREATOR_SELF_REVOKE_REASON,
            reviewerId = userId
        )

        invalidateCreatorLifecycleCaches(userId, result.productIds, result.postIds)

        return Messages.User.CREATOR_SELF_REVOKED  // 본인 액션 알림 없음
    }

    // 신청 취소
    suspend fun cancelApplication(userId: Int) {
        query {
            val application = applicationRepository.findByUserId(userId)
                ?: throw NotFoundException(Errors.User.NO_APPLICATION_FOUND)

            if (application.status != CreatorApplicationStatus.PENDING) {
                throw ValidationException(Errors.User.PENDING_APPLICATION_ONLY)
            }

            applicationRepository.deleteApplication(application.id.value)
        }
    }

    // 통계
    suspend fun getCreatorStatistics(): CreatorStatisticsResponse {
        val (todayStart, tomorrowStart, weekStart, monthStart) = statisticsDateBoundaries()

        return query {
            val avgApprovalTime = applicationRepository.avgApprovalTime()

            CreatorStatisticsResponse(
                totalApplications = applicationRepository.countAllApplications(),
                pendingApplications = applicationRepository.countByStatus(CreatorApplicationStatus.PENDING),
                approvedApplications = applicationRepository.countByStatus(CreatorApplicationStatus.APPROVED),
                rejectedApplications = applicationRepository.countByStatus(CreatorApplicationStatus.REJECTED),
                revokedApplications = applicationRepository.countByStatus(CreatorApplicationStatus.REVOKED),
                applicationsToday = applicationRepository.countApplicationsByDateRange(todayStart, tomorrowStart),
                applicationsThisWeek = applicationRepository.countApplicationsByDateRange(weekStart, tomorrowStart),
                applicationsThisMonth = applicationRepository.countApplicationsByDateRange(monthStart, tomorrowStart),
                totalActiveCreators = userRepository.countUsersByRole(UserRole.CREATOR),
                avgApprovalTime = String.format(Locale.ROOT, "%.1f", avgApprovalTime)
            )
        }
    }

    /** 해제·강등 사전조건 검사 */
    private suspend fun ensureNoActiveCommitments(creatorId: Int) {
        val activeSubscribers = subscriptionRepository.countCreatorSubscribers(creatorId)
        val inProgressOrders = orderRepository.countCreatorOrders(creatorId, CreatorOrderFilter.PENDING) +  // 미결제 주문도 셈
                orderRepository.countCreatorOrders(creatorId, CreatorOrderFilter.SHIPPED)

        if (activeSubscribers > 0 || inProgressOrders > 0) {
            val parts = buildList {
                if (activeSubscribers > 0) add("활성 구독자 ${activeSubscribers}명")
                if (inProgressOrders > 0) add("진행 중 주문 ${inProgressOrders}건")
            }
            throw ValidationException("${parts.joinToString(", ")}이 있어 해제할 수 없습니다.")
        }
    }

    /** 해제·강등 공용 teardown */
    private suspend fun teardownCreatorAssets(
        userId: Int,
        reason: String,
        reviewerId: Int
    ): CreatorTeardownResult {
        // 조건검사 → teardown → 강등
        return query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            if (user.role != UserRole.CREATOR) {
                throw ValidationException(Errors.User.NOT_CREATOR)
            }

            ensureNoActiveCommitments(userId)

            subscriptionPlanRepository.deactivatePlansByCreator(userId)
            val productIds = productRepository.discontinueProductsByCreator(userId)
            val postIds = postRepository.hideCreatorPosts(userId)
            couponRepository.disableActiveCouponsByCreator(userId)

            userRepository.demoteCreator(userId)

            val application = applicationRepository.findByUserId(userId)
            if (application != null) {
                application.status = CreatorApplicationStatus.REVOKED
                application.rejectionReason = reason
                application.reviewedBy = reviewerId
                application.reviewedAt = nowUtc()
            }

            CreatorTeardownResult(productIds, postIds)
        }
    }

    private suspend fun invalidateCreatorLifecycleCaches(
        userId: Int,
        productIds: List<Int>,
        postIds: List<Int>
    ) {
        cacheService.delete(CacheKeys.profile(userId))
        cacheService.deletePattern(CacheKeys.Patterns.subscriptionPlans(userId))
        cacheService.deletePattern(CacheKeys.Patterns.creatorStore(userId))
        cacheService.deletePattern(CacheKeys.Patterns.community(userId))
        cacheService.deletePattern(CacheKeys.Patterns.PINNED_POSTS)
        cacheService.deletePattern(CacheKeys.Patterns.CREATOR_PROFILE_SECTIONS)
        cacheService.deletePattern(CacheKeys.Patterns.EXPLORE_FEED)
        cacheService.deletePattern(CacheKeys.Patterns.TRENDING_FEED)
        cacheService.deletePattern(CacheKeys.Patterns.POPULAR_CREATORS)

        productIds.forEach { productId ->
            cacheService.deletePattern(CacheKeys.Patterns.product(productId))
            cacheService.deletePattern(CacheKeys.Patterns.relatedProducts(productId))
        }
        postIds.forEach { postId ->
            cacheService.deletePattern(CacheKeys.Patterns.post(postId))
        }
    }
}
