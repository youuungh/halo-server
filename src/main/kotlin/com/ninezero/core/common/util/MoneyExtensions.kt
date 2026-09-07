package com.ninezero.core.common.util

import java.math.BigDecimal
import java.math.RoundingMode

/** 금액을 정수 문자열로 직렬화 */
fun BigDecimal.toAmountString(): String =
    setScale(0, RoundingMode.HALF_UP).toPlainString()  // 소수점 있으면 앱에서 0원
