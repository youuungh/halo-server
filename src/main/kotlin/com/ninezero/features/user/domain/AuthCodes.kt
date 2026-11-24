package com.ninezero.features.user.domain

import com.ninezero.core.common.config.Constants
import kotlinx.datetime.LocalDateTime
import java.security.SecureRandom

/** 발송 실패 시 되돌릴 직전 발송 기록 */
internal data class CodeSendState(val sentCount: Int, val lastSentAt: LocalDateTime?)

private val secureRandom = SecureRandom()

/** 혼동 문자를 뺀 이메일 인증 코드 */
internal fun generateVerificationCode(): String =
    (1..Constants.User.VERIFICATION_CODE_LENGTH)
        .map { Constants.User.VERIFICATION_CODE_CHARSET[secureRandom.nextInt(Constants.User.VERIFICATION_CODE_CHARSET.length)] }  // 혼동 문자 제외 charset
        .joinToString("")

/** 숫자만 이어 붙인 난수 문자열 */
internal fun randomDigits(count: Int): String =
    buildString { repeat(count) { append(secureRandom.nextInt(10)) } }
