package com.ninezero.features.coupon.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.exception.InvalidCouponCodeException
import com.ninezero.core.common.exception.InvalidInputException
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

/** 쿠폰 입력 검증 */
object CouponValidation {
    private val COUPON_CODE_REGEX = Constants.Coupon.CODE_PATTERN.toRegex()

    fun validateCouponCode(code: String?) {
        when {
            code.isNullOrBlank() ->
                throw InvalidCouponCodeException("쿠폰 코드는 필수입니다.")
            code.length < Constants.Coupon.MIN_CODE_LENGTH || code.length > Constants.Coupon.MAX_CODE_LENGTH ->
                throw InvalidCouponCodeException("쿠폰 코드는 ${Constants.Coupon.MIN_CODE_LENGTH}-${Constants.Coupon.MAX_CODE_LENGTH}자여야 합니다.")
            !code.matches(COUPON_CODE_REGEX) ->
                throw InvalidCouponCodeException("쿠폰 코드는 영문 대문자와 숫자만 사용 가능합니다.")
        }
    }

    fun validateManualCouponCode(code: String?) {
        if (code.isNullOrBlank()) return  // null이면 자동생성

        when {
            code.length < Constants.Coupon.MIN_CODE_LENGTH ->
                throw InvalidCouponCodeException("쿠폰 코드는 최소 ${Constants.Coupon.MIN_CODE_LENGTH}자 이상이어야 합니다.")
            code.length > Constants.Coupon.MAX_CODE_LENGTH ->
                throw InvalidCouponCodeException("쿠폰 코드는 최대 ${Constants.Coupon.MAX_CODE_LENGTH}자까지 입력 가능합니다.")
            !code.matches(COUPON_CODE_REGEX) ->
                throw InvalidCouponCodeException("쿠폰 코드는 영문 대문자와 숫자만 사용 가능합니다.")
        }
    }

    fun validateCouponName(name: String?) {
        when {
            name.isNullOrBlank() ->
                throw InvalidInputException("쿠폰명은 필수입니다.")
            name.length < Constants.Coupon.MIN_NAME_LENGTH ->
                throw InvalidInputException("쿠폰명은 최소 ${Constants.Coupon.MIN_NAME_LENGTH}자 이상이어야 합니다.")
            name.length > Constants.Coupon.MAX_NAME_LENGTH ->
                throw InvalidInputException("쿠폰명은 최대 ${Constants.Coupon.MAX_NAME_LENGTH}자까지 입력 가능합니다.")
        }
    }

    fun validateCouponDescription(description: String?) {
        if (description.isNullOrBlank()) return

        when {
            description.length > Constants.Coupon.MAX_DESCRIPTION_LENGTH ->
                throw InvalidInputException("쿠폰 설명은 최대 ${Constants.Coupon.MAX_DESCRIPTION_LENGTH}자까지 입력 가능합니다.")
        }
    }

    fun validateCouponDiscountRate(rate: Int) {
        when {
            rate < Constants.Coupon.MIN_DISCOUNT_RATE ->
                throw InvalidInputException("할인율은 최소 ${Constants.Coupon.MIN_DISCOUNT_RATE}% 이상이어야 합니다.")
            rate > Constants.Coupon.MAX_DISCOUNT_RATE ->
                throw InvalidInputException("할인율은 최대 ${Constants.Coupon.MAX_DISCOUNT_RATE}%까지 설정 가능합니다.")
        }
    }

    fun validateCouponDiscountAmount(amount: String?) {
        if (amount.isNullOrBlank()) {
            throw InvalidInputException("할인 금액은 필수입니다.")
        }

        try {
            val value = BigDecimal(amount)
            when {
                value < Constants.Coupon.MIN_DISCOUNT_AMOUNT ->
                    throw InvalidInputException("할인 금액은 최소 ${Constants.Coupon.MIN_DISCOUNT_AMOUNT}원 이상이어야 합니다.")
                value > Constants.Coupon.MAX_DISCOUNT_AMOUNT ->
                    throw InvalidInputException("할인 금액은 최대 ${Constants.Coupon.MAX_DISCOUNT_AMOUNT}원까지 설정 가능합니다.")
            }
        } catch (_: NumberFormatException) {
            throw InvalidInputException("올바른 금액 형식이 아닙니다.")
        }
    }

    fun validateCouponMinOrderAmount(amount: String?) {
        if (amount.isNullOrBlank()) return

        try {
            val value = BigDecimal(amount)
            when {
                value < Constants.Coupon.MIN_ORDER_AMOUNT ->
                    throw InvalidInputException("최소 주문 금액은 ${Constants.Coupon.MIN_ORDER_AMOUNT}원 이상이어야 합니다.")
            }
        } catch (_: NumberFormatException) {
            throw InvalidInputException("올바른 금액 형식이 아닙니다.")
        }
    }

    fun validateCouponMaxDiscountAmount(amount: String?) {
        if (amount.isNullOrBlank()) return

        try {
            val value = BigDecimal(amount)
            when {
                value <= BigDecimal.ZERO ->
                    throw InvalidInputException("최대 할인 금액은 0원보다 커야 합니다.")
                value > Constants.Coupon.MAX_DISCOUNT_AMOUNT ->
                    throw InvalidInputException("최대 할인 금액은 최대 ${Constants.Coupon.MAX_DISCOUNT_AMOUNT}원까지 설정 가능합니다.")
            }
        } catch (_: NumberFormatException) {
            throw InvalidInputException("올바른 금액 형식이 아닙니다.")
        }
    }

    fun validateCouponQuantity(quantity: Int) {
        when {
            quantity < Constants.Coupon.MIN_QUANTITY ->
                throw InvalidInputException("발급 수량은 최소 ${Constants.Coupon.MIN_QUANTITY}개 이상이어야 합니다.")
            quantity > Constants.Coupon.MAX_QUANTITY ->
                throw InvalidInputException("발급 수량은 최대 ${Constants.Coupon.MAX_QUANTITY}개까지 설정 가능합니다.")
        }
    }

    fun validateCouponUseCount(useCount: Int) {
        when {
            useCount < 1 ->
                throw InvalidInputException("사용 횟수는 최소 1회 이상이어야 합니다.")
            useCount > Constants.Coupon.MAX_USE_COUNT_PER_USER ->
                throw InvalidInputException("사용 횟수는 최대 ${Constants.Coupon.MAX_USE_COUNT_PER_USER}회까지 설정 가능합니다.")
        }
    }

    fun validateCouponTypeFields(
        type: CouponType,
        discountRate: Int?,
        discountAmount: String?
    ) {
        when (type) {
            CouponType.PERCENTAGE -> {
                if (discountRate == null) {
                    throw InvalidInputException("할인율 쿠폰은 할인율이 필수입니다.")
                }
                validateCouponDiscountRate(discountRate)
            }
            CouponType.FIXED_AMOUNT -> {
                if (discountAmount.isNullOrBlank()) {
                    throw InvalidInputException("정액 할인 쿠폰은 할인 금액이 필수입니다.")
                }
                validateCouponDiscountAmount(discountAmount)
            }
            else -> {}
        }
    }

    fun validateDateRange(startDate: LocalDateTime, endDate: LocalDateTime) {
        when {
            startDate >= endDate ->
                throw InvalidInputException("종료일은 시작일보다 이후여야 합니다.")
        }
    }
}
