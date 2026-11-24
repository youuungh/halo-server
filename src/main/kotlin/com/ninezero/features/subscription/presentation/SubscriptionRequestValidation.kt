package com.ninezero.features.subscription.presentation

import com.ninezero.core.common.util.validateRequest
import com.ninezero.features.subscription.domain.SubscriptionValidation
import com.ninezero.features.subscription.presentation.models.request.SubscribeRequest
import com.ninezero.features.subscription.presentation.models.request.SubscriptionPlanRequest
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig

/** 플랜·구독 요청 본문 검사 */
fun RequestValidationConfig.validateSubscriptionRequests() {
    validate<SubscriptionPlanRequest> {
        validateRequest {
            SubscriptionValidation.validatePrice(it.price, "플랜 가격")
        }
    }

    validate<SubscribeRequest> {
        validateRequest {
            SubscriptionValidation.validatePlanId(it.planId)
        }
    }
}
