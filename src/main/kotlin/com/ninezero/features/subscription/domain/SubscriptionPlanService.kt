package com.ninezero.features.subscription.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
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
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.subscription.presentation.models.request.SubscriptionPlanRequest
import com.ninezero.features.subscription.presentation.models.request.UpdatePlanRequest
import com.ninezero.features.subscription.presentation.models.response.SubscriptionPlanListResponse
import com.ninezero.features.subscription.presentation.models.response.SubscriptionPlanResponse
import com.ninezero.features.subscription.toSubscriptionPlanResponse
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toUserSummaryResponse
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

        val name = request.name.trim()
        val description = request.description.trim()

        ValidationUtils.validatePlanName(name)
        ValidationUtils.validatePlanDescription(description)
        ValidationUtils.validatePlanBenefits(request.benefits)

        val price = try {
            BigDecimal(request.price)
        } catch (_: Exception) {
            throw InvalidPriceException(Errors.Commerce.Product.INVALID_PRICE_FORMAT)
        }

        if (price <= BigDecimal.ZERO) {
            throw InvalidPriceException(Errors.Subscription.PAID_PLAN_PRICE_INVALID)
        }

        if (price > BigDecimal(Constants.Subscription.MAX_PLAN_PRICE)) {
            throw InvalidPriceException(Errors.Subscription.PLAN_PRICE_RANGE_INVALID)
        }

        val response = query {
            val creator = userRepository.findUserById(creatorId)
                ?: throw UserNotFoundException(creatorId)

            if (creator.role != UserRole.CREATOR && creator.role != UserRole.ADMIN) {
                throw CreatorOnlyException()
            }

            if (planRepository.existsActivePlanForTier(creatorId, request.tier)) {
                throw ConflictException(Errors.Subscription.SAME_TIER_PLAN_EXISTS)
            }
            requireTierPriceOrder(creatorId, request.tier, price, excludePlanId = null)

            val benefitsJson = request.benefits.encodeToJson()

            planRepository.createPlan(
                creatorId = creatorId,
                name = name,
                tier = request.tier,
                description = description,
                price = price,
                benefits = benefitsJson
            ).toSubscriptionPlanResponse(creator.toUserSummaryResponse(), subscriberCount = 0)  // 새 플랜
        }

        cacheService.deletePattern(CacheKeys.Patterns.subscriptionPlans(creatorId))
        cacheService.delete(CacheKeys.profile(creatorId))  // 프로필 구독 버튼 노출 여부

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

            val creatorSummary = creator.toUserSummaryResponse()
            val subscriberCounts = subscriptionRepository.countPlanSubscribers(plans.map { it.id.value })
            val planResponses = plans.map { it.toSubscriptionPlanResponse(creatorSummary, subscriberCounts[it.id.value] ?: 0) }

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

            val plans = planRepository.findPlansByCreator(creatorId, validPage, validLimit)  // 삭제 제외, 비활성 포함
            val totalCount = planRepository.countPlansByCreator(creatorId)

            val creatorSummary = creator.toUserSummaryResponse()
            val subscriberCounts = subscriptionRepository.countPlanSubscribers(plans.map { it.id.value })
            val planResponses = plans.map { it.toSubscriptionPlanResponse(creatorSummary, subscriberCounts[it.id.value] ?: 0) }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(planResponses, pagination)
        }
    }

    suspend fun updatePlan(
        planId: Int,
        creatorId: Int,
        request: UpdatePlanRequest
    ): SubscriptionPlanResponse {
        val name = request.name?.trim()
        val description = request.description?.trim()

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
                if (p > BigDecimal(Constants.Subscription.MAX_PLAN_PRICE)) {
                    throw InvalidPriceException(Errors.Subscription.PLAN_PRICE_RANGE_INVALID)
                }
                p
            } catch (_: NumberFormatException) {
                throw InvalidPriceException(Errors.Commerce.Product.INVALID_PRICE_FORMAT)
            }
        }

        val response = query {
            val plan = verifyPlanOwnership(planId, creatorId, Errors.Subscription.PLAN_UPDATE_PERMISSION_DENIED)

            val creator = userRepository.findUserById(creatorId)
                ?: throw UserNotFoundException(creatorId)
            if (creator.role != UserRole.CREATOR && creator.role != UserRole.ADMIN) {  // 해제된 크리에이터 차단
                throw ForbiddenException(Errors.Subscription.PLAN_UPDATE_PERMISSION_DENIED)
            }

            // 비활성 플랜을 되살릴 때도 생성과 같은 티어 중복 검사
            if (request.isActive == true && !plan.isActive &&
                planRepository.existsActivePlanForTier(creatorId, plan.tier, excludePlanId = planId)) {
                throw ConflictException(Errors.Subscription.SAME_TIER_PLAN_EXISTS)
            }
            // 활성 플랜 가격이 바뀌면 등급 순서 검사
            if ((request.isActive ?: plan.isActive) && (price != null || request.isActive == true)) {
                requireTierPriceOrder(creatorId, plan.tier, price ?: plan.price, excludePlanId = planId)
            }

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

            updatedPlan.toSubscriptionPlanResponse(creator.toUserSummaryResponse(), subscriptionRepository.countPlanSubscribers(planId))
        }

        cacheService.deletePattern(CacheKeys.Patterns.subscriptionPlans(creatorId))  // 캐시 무효화
        cacheService.delete(CacheKeys.profile(creatorId))  // 프로필 구독 버튼 노출 여부

        return response
    }

    suspend fun deletePlan(planId: Int, creatorId: Int): String {
        query {
            verifyPlanOwnership(planId, creatorId, Errors.Subscription.PLAN_DELETE_PERMISSION_DENIED)

            val activeSubscriberCount = subscriptionRepository.countPlanSubscribers(planId)
            if (activeSubscriberCount > 0) {
                throw ValidationException(Errors.Subscription.ACTIVE_SUBSCRIPTIONS_EXIST)
            }

            val deleted = planRepository.deletePlan(planId)
            if (!deleted) {
                throw InternalServerException(Errors.Subscription.PLAN_DELETE_FAILED)
            }
        }

        cacheService.deletePattern(CacheKeys.Patterns.subscriptionPlans(creatorId))
        cacheService.delete(CacheKeys.profile(creatorId))  // 프로필 구독 버튼 노출 여부

        return Messages.Subscription.PLAN_DELETED
    }

    /** 활성 플랜끼리 상위 등급이 더 비싸야 함 */
    private suspend fun requireTierPriceOrder(
        creatorId: Int,
        tier: SubscriptionPlanTier,
        price: BigDecimal,
        excludePlanId: Int?
    ) {
        val violates = planRepository.findActivePlansByCreator(creatorId, page = 1, limit = Constants.MAX_PAGE_LIMIT)
            .filter { it.id.value != excludePlanId }
            .any { other ->
                (other.tier < tier && other.price >= price) || (other.tier > tier && other.price <= price)
            }
        if (violates) throw InvalidPriceException(Errors.Subscription.TIER_PRICE_ORDER_INVALID)
    }

    private suspend fun verifyPlanOwnership(
        planId: Int,
        creatorId: Int,
        errorMessage: String
    ) = planRepository.findPlanById(planId)
        ?.takeUnless { it.isDeleted }  // 삭제된 플랜은 수정·삭제 대상 아님
        ?.also { plan ->
            if (plan.creatorId != creatorId) {
                throw ForbiddenException(errorMessage)
            }
        }
        ?: throw SubscriptionPlanNotFoundException(planId)
}
