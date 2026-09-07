package com.ninezero.features.subscription.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.CreatorOnlyException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.InternalServerException
import com.ninezero.core.common.exception.InvalidPriceException
import com.ninezero.core.common.exception.SubscriptionPlanNotFoundException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.exception.ValidationException
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.encodeToJson
import com.ninezero.core.common.util.getJson
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.setJson
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.subscription.presentation.models.request.SubscriptionPlanRequest
import com.ninezero.features.subscription.presentation.models.request.UpdatePlanRequest
import com.ninezero.features.subscription.presentation.models.response.SubscriptionListResponse
import com.ninezero.features.subscription.presentation.models.response.SubscriptionPlanListResponse
import com.ninezero.features.subscription.presentation.models.response.SubscriptionPlanResponse
import com.ninezero.features.subscription.toPlanResponse
import com.ninezero.features.subscription.toSubscriptionResponse
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours

class SubscriptionPlanService(
    private val planRepository: SubscriptionPlanRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val userRepository: UserRepository,
    private val cacheService: CacheService
) {

    suspend fun createPlan(creatorId: Int, request: SubscriptionPlanRequest): SubscriptionPlanResponse {
        if (request.tier == SubscriptionPlanTier.FREE) {
            throw ValidationException(Errors.Subscription.FREE_TIER_CANNOT_CREATE)
        }

        ValidationUtils.validatePlanName(request.name)
        ValidationUtils.validatePlanDescription(request.description)
        ValidationUtils.validatePlanBenefits(request.benefits)

        val price = try {
            BigDecimal(request.price)
        } catch (_: Exception) {
            throw InvalidPriceException(Errors.Commerce.Product.INVALID_PRICE_FORMAT)
        }

        if (price <= BigDecimal.ZERO) {
            throw InvalidPriceException(Errors.Subscription.PAID_PLAN_PRICE_INVALID)
        }

        if (price < BigDecimal(Constants.Subscription.MIN_PLAN_PRICE) ||
            price > BigDecimal(Constants.Subscription.MAX_PLAN_PRICE)) {
            throw InvalidPriceException(Errors.Subscription.PLAN_PRICE_RANGE_INVALID)
        }

        val plan = query {
            val creator = userRepository.findUserById(creatorId)
                ?: throw UserNotFoundException(creatorId)

            if (creator.role != UserRole.CREATOR && creator.role != UserRole.ADMIN) {
                throw CreatorOnlyException()
            }

            val existingPlans = planRepository.findPlansByCreator(creatorId, 1, 100)
            val sameTierPlan = existingPlans.firstOrNull {
                it.tier == request.tier && it.isActive
            }

            if (sameTierPlan != null) {
                throw ConflictException("${Errors.Subscription.SAME_TIER_PLAN_EXISTS}: '${sameTierPlan.name}'")  // 같은 티어 활성 플랜 있으면 충돌
            }

            val name = ValidationUtils.sanitizeHtml(request.name)
            val description = ValidationUtils.sanitizeHtml(request.description)
            val benefitsJson = request.benefits.encodeToJson()

            planRepository.createPlan(
                creatorId = creatorId,
                name = name,
                tier = request.tier,
                description = description,
                price = price,
                benefits = benefitsJson
            )
        }

        val creatorSummary = query {
            val creator = userRepository.findUserById(creatorId)!!
            creator.toSummaryResponse()
        }

        val response = plan.toPlanResponse(creatorSummary)

        cacheService.deletePattern(CacheKeys.Patterns.subscriptionPlans(creatorId))

        return response
    }

    suspend fun getCreatorPlans(
        creatorId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): SubscriptionPlanListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        val cacheKey = CacheKeys.subscriptionPlans(creatorId, validPage, validLimit)
        cacheService.getJson<SubscriptionPlanListResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val creator = userRepository.findUserById(creatorId)
                ?: throw UserNotFoundException(creatorId)

            val plans = planRepository.findActivePlansByCreator(creatorId, validPage, validLimit)
            val totalCount = planRepository.countActivePlansByCreator(creatorId)

            val creatorSummary = creator.toSummaryResponse()
            val planResponses = plans.map { it.toPlanResponse(creatorSummary) }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(planResponses, pagination)
        }

        cacheService.setJson(cacheKey, response, ttl = 1.hours)

        return response
    }

    suspend fun getMyPlans(
        creatorId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): SubscriptionPlanListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val creator = userRepository.findUserById(creatorId)
                ?: throw UserNotFoundException(creatorId)

            if (creator.role != UserRole.CREATOR && creator.role != UserRole.ADMIN) {
                throw CreatorOnlyException()
            }

            val plans = planRepository.findPlansByCreator(creatorId, validPage, validLimit)  // 비활성 플랜 포함
            val totalCount = planRepository.countPlansByCreator(creatorId)

            val creatorSummary = creator.toSummaryResponse()
            val planResponses = plans.map { it.toPlanResponse(creatorSummary) }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(planResponses, pagination)
        }
    }

    suspend fun getPlanById(planId: Int): SubscriptionPlanResponse {
        val cacheKey = CacheKeys.subscriptionPlan(planId)
        cacheService.getJson<SubscriptionPlanResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val plan = planRepository.findPlanById(planId)
                ?: throw SubscriptionPlanNotFoundException(planId)

            if (!plan.isActive) {
                throw ValidationException(Errors.Subscription.PLAN_NOT_ACTIVE)  // 비활성 플랜은 예외
            }

            val creator = userRepository.findUserById(plan.creatorId)
                ?: throw UserNotFoundException(plan.creatorId)

            val creatorSummary = creator.toSummaryResponse()
            plan.toPlanResponse(creatorSummary)
        }

        cacheService.setJson(cacheKey, response, ttl = 1.hours)

        return response
    }

    suspend fun updatePlan(
        planId: Int,
        creatorId: Int,
        request: UpdatePlanRequest
    ): SubscriptionPlanResponse {
        if (request.tier == SubscriptionPlanTier.FREE) {
            throw ValidationException(Errors.Subscription.CANNOT_CHANGE_TO_FREE_TIER)
        }

        val name = request.name?.let { ValidationUtils.sanitizeHtml(it) }
        val description = request.description?.let { ValidationUtils.sanitizeHtml(it) }

        name?.let {
            ValidationUtils.validatePlanName(it)
        }

        description?.let {
            ValidationUtils.validatePlanDescription(it)
        }

        request.benefits?.let {
            ValidationUtils.validatePlanBenefits(it)
        }

        val price = request.price?.let {
            try {
                val p = BigDecimal(it)
                if (p <= BigDecimal.ZERO) {
                    throw InvalidPriceException(Errors.Subscription.PAID_PLAN_PRICE_INVALID)
                }
                if (p < BigDecimal(Constants.Subscription.MIN_PLAN_PRICE) ||
                    p > BigDecimal(Constants.Subscription.MAX_PLAN_PRICE)) {
                    throw InvalidPriceException(Errors.Subscription.PLAN_PRICE_RANGE_INVALID)
                }
                p
            } catch (_: NumberFormatException) {
                throw InvalidPriceException(Errors.Commerce.Product.INVALID_PRICE_FORMAT)
            }
        }

        val response = query {
            verifyPlanOwnership(planId, creatorId, Errors.Subscription.PLAN_UPDATE_PERMISSION_DENIED)

            val benefitsJson = request.benefits?.encodeToJson()
            val updated = planRepository.updatePlan(
                planId = planId,
                name = name,
                description = description,
                price = price,
                benefits = benefitsJson,
                isActive = request.isActive
            )

            if (!updated) {
                throw InternalServerException(Errors.Subscription.PLAN_UPDATE_FAILED)
            }

            val updatedPlan = planRepository.findPlanById(planId)!!
            val creator = userRepository.findUserById(creatorId)!!

            updatedPlan.toPlanResponse(creator.toSummaryResponse())
        }

        cacheService.delete(CacheKeys.subscriptionPlan(planId))
        cacheService.deletePattern(CacheKeys.Patterns.subscriptionPlans(creatorId))  // 캐시 무효화

        return response
    }

    suspend fun deactivatePlan(planId: Int, creatorId: Int): String {
        query {
            verifyPlanOwnership(planId, creatorId, Errors.Subscription.PLAN_DEACTIVATE_PERMISSION_DENIED)

            val activeSubscriberCount = subscriptionRepository.countPlanSubscribers(planId)
            if (activeSubscriberCount > 0) {
                throw ValidationException(Errors.Subscription.ACTIVE_SUBSCRIPTIONS_EXIST)
            }

            val deleted = planRepository.deletePlan(planId)  // 소프트 삭제
            if (!deleted) {
                throw InternalServerException(Errors.Subscription.PLAN_DEACTIVATE_FAILED)
            }
        }

        cacheService.delete(CacheKeys.subscriptionPlan(planId))
        cacheService.deletePattern(CacheKeys.Patterns.subscriptionPlans(creatorId))

        return Messages.Subscription.PLAN_DEACTIVATED
    }

    suspend fun getPlanSubscribers(
        planId: Int,
        creatorId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): SubscriptionListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val plan = verifyPlanOwnership(planId, creatorId, Errors.Subscription.SUBSCRIBERS_VIEW_PERMISSION_DENIED)

            val subscriptions = subscriptionRepository.findPlanSubscribers(
                planId, validPage, validLimit
            )
            val totalCount = subscriptionRepository.countPlanSubscribers(planId)

            val creator = userRepository.findUserById(creatorId)!!
            val creatorSummary = creator.toSummaryResponse()

            val subscriptionResponses = subscriptions.map { subscription ->
                subscription.toSubscriptionResponse(
                    creator = creatorSummary,
                    planName = plan.name,
                    planPrice = plan.price.toAmountString()
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            SubscriptionListResponse(
                subscriptions = createPagedResponse(subscriptionResponses, pagination),
                activeCount = subscriptionResponses.size
            )
        }
    }

    private suspend fun verifyPlanOwnership(
        planId: Int,
        creatorId: Int,
        errorMessage: String
    ) = planRepository.findPlanById(planId)
        ?.also { plan ->
            if (plan.creatorId != creatorId) {
                throw ForbiddenException(errorMessage)
            }
        }
        ?: throw SubscriptionPlanNotFoundException(planId)
}
