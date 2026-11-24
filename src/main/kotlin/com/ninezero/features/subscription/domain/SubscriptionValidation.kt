package com.ninezero.features.subscription.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.InvalidPriceException

/** 멤버십 플랜 입력 검증 */
object SubscriptionValidation {
    fun validatePrice(price: String, fieldName: String = "가격") {
        when {
            price.isBlank() ->
                throw InvalidPriceException("${fieldName}은 필수입니다.")
        }
    }

    fun validatePlanName(name: String) {
        when {
            name.isBlank() ->
                throw InvalidInputException("플랜명은 필수입니다.")
            name.length > Constants.Subscription.MAX_PLAN_NAME_LENGTH ->
                throw InvalidInputException("플랜명은 ${Constants.Subscription.MAX_PLAN_NAME_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validatePlanDescription(description: String) {
        when {
            description.isBlank() ->
                throw InvalidInputException("플랜 설명은 필수입니다.")
            description.length > Constants.Subscription.MAX_PLAN_DESCRIPTION_LENGTH ->
                throw InvalidInputException("플랜 설명은 ${Constants.Subscription.MAX_PLAN_DESCRIPTION_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validatePlanBenefits(benefits: List<String>) {
        when {
            benefits.isEmpty() ->
                throw InvalidInputException("최소 하나 이상의 혜택을 입력해야 합니다.")
            benefits.size > Constants.Subscription.MAX_BENEFITS_PER_PLAN ->
                throw InvalidInputException("혜택은 최대 ${Constants.Subscription.MAX_BENEFITS_PER_PLAN}개까지 입력 가능합니다.")
        }
    }

    fun validatePlanId(planId: Int) {
        when {
            planId <= 0 ->
                throw InvalidInputException(Errors.Common.INVALID_ID)
        }
    }
}
