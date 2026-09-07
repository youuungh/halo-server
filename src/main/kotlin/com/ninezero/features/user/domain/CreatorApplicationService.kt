package com.ninezero.features.user.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CreatorApplicationStatus
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
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
import kotlinx.datetime.*

class CreatorApplicationService(
    private val applicationRepository: CreatorApplicationRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val coroutineScope: CoroutineScope,
    private val subscriptionRepository: SubscriptionRepository,
    private val orderRepository: OrderRepository,
    private val subscriptionPlanRepository: SubscriptionPlanRepository,
    private val productRepository: ProductRepository,
    private val postRepository: PostRepository,
    private val couponRepository: CouponRepository,
    private val cacheService: CacheService
) {
    private val logger = logger()

    private data class CreatorTeardownResult(
        val productIds: List<Int>,
        val postIds: List<Int>
    )

    // 해제·강등 공통
    private suspend fun requireAdmin(userId: Int) {
        val user = query {
            userRepository.findUserById(userId) ?: throw UserNotFoundException(userId)
        }
        if (user.role != UserRole.ADMIN) {
            throw ForbiddenException(Errors.Common.ADMIN_ONLY)
        }
    }

    /** 해제·강등 사전조건 검사 */
    private suspend fun ensureNoActiveCommitments(creatorId: Int) {
        val activeSubscribers = subscriptionRepository.countCreatorSubscribers(creatorId)
        val inProgressOrders = orderRepository.countCreatorOrders(creatorId, "PENDING") +
                orderRepository.countCreatorOrders(creatorId, "SHIPPED")

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
        cacheService.delete(CacheKeys.user(userId))
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

        val existingApp = query {
            applicationRepository.findByUserId(userId)
        }

        if (existingApp != null) {
            when (existingApp.status) {
                CreatorApplicationStatus.PENDING -> {
                    throw ConflictException(Errors.User.PENDING_APPLICATION_EXISTS)  // PENDING 중복 신청 차단
                }
                CreatorApplicationStatus.APPROVED -> {
                    if (user.role == UserRole.CREATOR) {
                        throw ValidationException(Errors.User.ALREADY_CREATOR)
                    }
                }
                CreatorApplicationStatus.REJECTED,
                CreatorApplicationStatus.REVOKED -> Unit
            }
        }

        val lastRejected = query {
            applicationRepository.findLastRejectedByUserId(userId)
        }

        if (lastRejected?.reviewedAt != null) {
            val now = nowUtc()
            val reviewedAt = lastRejected.reviewedAt!!
            val nowInstant = now.toInstant(TimeZone.UTC)
            val reviewedInstant = reviewedAt.toInstant(TimeZone.UTC)
            val daysDiff = (nowInstant - reviewedInstant).inWholeDays

            if (daysDiff < Constants.User.CREATOR_REAPPLY_COOLDOWN_DAYS) {
                val remainingDays = Constants.User.CREATOR_REAPPLY_COOLDOWN_DAYS - daysDiff
                throw ValidationException(
                    "거절 후 ${Constants.User.CREATOR_REAPPLY_COOLDOWN_DAYS}일 이후에 재신청 가능합니다. (${remainingDays}일 남음)"
                )
            }
        }

        val reason = ValidationUtils.sanitizeHtml(request.reason)
        val portfolioUrl = request.portfolioUrl?.let { ValidationUtils.sanitizeUrl(it) }

        ValidationUtils.validateCreatorApplicationReason(reason)

        val application = query {
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

            Pair(foundUser, foundApp)
        }

        return application.toCreatorApplicationResponse(user.username)
    }

    suspend fun getApplications(
        adminId: Int,
        status: CreatorApplicationStatus?,
        page: Int,
        limit: Int,
        search: String? = null,
        sortBy: String? = null,
        sortOrder: String? = null
    ): CreatorApplicationListResponse {
        requireAdmin(adminId)
        val (applications, total) = query {
            val apps = applicationRepository.findApplicationsByStatus(status, page, limit, search, sortBy, sortOrder)
            val count = applicationRepository.countByStatus(status, search)

            Pair(apps, count)
        }

        val userIds = applications.map { it.userId }.distinct()
        val users = query {
            userRepository.findUsersByIds(userIds).associateBy { it.id.value }
        }

        val responses = applications.map { app ->
            val user = users[app.userId]
            app.toCreatorApplicationResponse(user?.username ?: "Unknown")
        }

        val pagination = PaginationInfo(page, limit, total)
        return createPagedResponse(responses, pagination)
    }

    // 승인/거절
    suspend fun approveApplication(
        applicationId: Int,
        adminId: Int
    ): String {
        requireAdmin(adminId)
        val (applicantUserId, restoredPostIds) = query {
            val userId = applicationRepository.approveApplication(applicationId, adminId)
            userRepository.promoteToCreator(userId)
            // HIDDEN 마커 글만 복구
            val restored = postRepository.restoreCreatorHiddenPosts(userId)
            Pair(userId, restored)
        }

        invalidateCreatorLifecycleCaches(applicantUserId, emptyList(), restoredPostIds)

        // 알림 전송
        coroutineScope.launch {
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
        requireAdmin(adminId)
        val rejectionReason = ValidationUtils.sanitizeHtml(request.rejectionReason)
        ValidationUtils.validateRejectionReason(rejectionReason)

        val applicantUserId = query {
            applicationRepository.rejectApplication(applicationId, adminId, rejectionReason)
        }

        // 알림 전송
        coroutineScope.launch {
            try {
                notificationService.sendCreatorRejectedNotification(
                    userId = applicantUserId,
                    applicationId = applicationId,
                    rejectionReason = request.rejectionReason
                )
            } catch (e: Exception) {
                logger.warn("알림 발송 실패: {}", e.message)
            }
        }

        return Messages.User.APPLICATION_REJECTED
    }

    // 강등/셀프 해제
    suspend fun demoteCreator(userId: Int, reason: String, adminId: Int): String {
        requireAdmin(adminId)
        val sanitizedReason = ValidationUtils.sanitizeHtml(reason)
        ValidationUtils.validateRejectionReason(sanitizedReason)

        val result = teardownCreatorAssets(
            userId = userId,
            reason = sanitizedReason,
            reviewerId = adminId
        )

        invalidateCreatorLifecycleCaches(userId, result.productIds, result.postIds)

        // 알림 전송
        coroutineScope.launch {
            try {
                notificationService.sendCreatorDemotedNotification(userId, sanitizedReason)
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

        return Messages.User.CREATOR_SELF_REVOKED  // 본인 액션이라 알림 없음
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
    suspend fun getCreatorStatistics(adminId: Int): CreatorStatisticsResponse {
        requireAdmin(adminId)
        val (todayStart, todayEnd, weekStart, monthStart) = statisticsDateBoundaries()

        return query {
            val avgApprovalTime = applicationRepository.avgApprovalTime()

            CreatorStatisticsResponse(
                totalApplications = applicationRepository.countAllApplications(),
                pendingApplications = applicationRepository.countByStatus(CreatorApplicationStatus.PENDING),
                approvedApplications = applicationRepository.countByStatus(CreatorApplicationStatus.APPROVED),
                rejectedApplications = applicationRepository.countByStatus(CreatorApplicationStatus.REJECTED),
                revokedApplications = applicationRepository.countByStatus(CreatorApplicationStatus.REVOKED),
                applicationsToday = applicationRepository.countApplicationsByDateRange(todayStart, todayEnd),
                applicationsThisWeek = applicationRepository.countApplicationsByDateRange(weekStart, todayEnd),
                applicationsThisMonth = applicationRepository.countApplicationsByDateRange(monthStart, todayEnd),
                totalActiveCreators = userRepository.countUsersByRole(UserRole.CREATOR),
                avgApprovalTime = String.format("%.1f", avgApprovalTime)
            )
        }
    }
}