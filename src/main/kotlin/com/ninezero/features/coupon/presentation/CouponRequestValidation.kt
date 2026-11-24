package com.ninezero.features.coupon.presentation

import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.util.validateRequest
import com.ninezero.features.coupon.domain.CouponValidation
import com.ninezero.features.coupon.presentation.models.request.ClaimCouponRequest
import com.ninezero.features.coupon.presentation.models.request.CouponRequest
import com.ninezero.features.coupon.presentation.models.request.UpdateCouponRequest
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig

/** 쿠폰 요청 본문 검사 */
fun RequestValidationConfig.validateCouponRequests() {
    validate<CouponRequest> {
        validateRequest {
            CouponValidation.validateManualCouponCode(it.code)
            CouponValidation.validateCouponName(it.name)
            CouponValidation.validateCouponDescription(it.description)

            when (it.type) {
                CouponType.PERCENTAGE -> {
                    val rate = it.discountValue.toIntOrNull()
                        ?: throw InvalidInputException("할인율은 숫자여야 합니다.")
                    CouponValidation.validateCouponDiscountRate(rate)
                }

                CouponType.FIXED_AMOUNT -> {
                    CouponValidation.validateCouponDiscountAmount(it.discountValue)
                }

                CouponType.FREE_SHIPPING -> {
                    // 검증 불필요
                }
            }

            CouponValidation.validateCouponMinOrderAmount(it.minOrderAmount)
            CouponValidation.validateCouponQuantity(it.totalQuantity)
            CouponValidation.validateCouponUseCount(it.maxUseCount)
        }
    }

    validate<UpdateCouponRequest> {
        validateRequest {
            it.name?.let { name -> CouponValidation.validateCouponName(name) }
            it.description?.let { description -> CouponValidation.validateCouponDescription(description) }
            it.minOrderAmount?.let { amount -> CouponValidation.validateCouponMinOrderAmount(amount) }
            it.totalQuantity?.let { quantity -> CouponValidation.validateCouponQuantity(quantity) }
            it.maxUseCount?.let { count -> CouponValidation.validateCouponUseCount(count) }
        }
    }

    validate<ClaimCouponRequest> {
        validateRequest {
            CouponValidation.validateCouponCode(it.code)
        }
    }
}
