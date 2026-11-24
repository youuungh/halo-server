package com.ninezero.features.point.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.InvalidPointException
import java.math.BigDecimal

/** 포인트 금액·사유 검증 */
object PointValidation {
    fun validatePointAmount(amount: BigDecimal) {
        when {
            amount < BigDecimal.ZERO ->
                throw InvalidPointException("포인트 금액은 0 이상이어야 합니다.")
            amount > Constants.Point.MAX_AMOUNT_DECIMAL ->
                throw InvalidPointException("포인트 금액은 최대 ${"%,d".format(Constants.Point.MAX_AMOUNT_DECIMAL.toBigInteger())}원까지 가능합니다.")
        }
    }

    fun validatePointReason(reason: String) {
        when {
            reason.isBlank() ->
                throw InvalidInputException("포인트 사유는 필수입니다.")
            reason.length > Constants.Point.MAX_REASON_LENGTH ->
                throw InvalidInputException("포인트 사유는 최대 ${Constants.Point.MAX_REASON_LENGTH}자까지 입력 가능합니다.")
        }
    }
}
