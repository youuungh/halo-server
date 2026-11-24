package com.ninezero.core.common.util

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.InvalidEmailException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.InvalidPasswordException

/** 이메일·비밀번호·전화번호·ID 목록 검증 */
object CommonValidation {
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")
    val PHONE_REGEX = Regex("^\\d{2,3}-\\d{3,4}-\\d{4}$")

    /** 대소문자 구분 없는 이메일 저장 형태 */
    fun normalizeEmail(email: String): String = email.trim().lowercase()

    fun validateEmail(email: String) {
        when {
            email.isBlank() ->
                throw InvalidEmailException("이메일은 필수입니다.")
            !EMAIL_REGEX.matches(email) ->
                throw InvalidEmailException("유효한 이메일 형식이 아닙니다.")
            email.length > Constants.User.MAX_EMAIL_LENGTH ->
                throw InvalidEmailException("이메일은 ${Constants.User.MAX_EMAIL_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validatePassword(password: String, fieldName: String = "비밀번호") {
        when {
            password.isBlank() ->
                throw InvalidPasswordException("${fieldName}는 필수입니다.")
            password.length < Constants.User.MIN_PASSWORD_LENGTH ->
                throw InvalidPasswordException("${fieldName}는 최소 ${Constants.User.MIN_PASSWORD_LENGTH}자 이상이어야 합니다.")
            password.length > Constants.User.MAX_PASSWORD_LENGTH ->
                throw InvalidPasswordException("${fieldName}는 ${Constants.User.MAX_PASSWORD_LENGTH}자를 초과할 수 없습니다.")
            password.contains(' ') ->
                throw InvalidPasswordException("${fieldName}에 공백을 포함할 수 없습니다.")
        }

        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() && it != ' ' }

        if (listOf(hasLetter, hasDigit, hasSpecial).count { it } < 2) {
            throw InvalidPasswordException("${fieldName}는 영문, 숫자, 특수문자 중 2가지 이상을 조합해야 합니다.")
        }
    }

    fun validateTargetIds(targetIds: List<Int>?) {
        if (targetIds == null) return

        when {
            targetIds.isEmpty() ->
                throw InvalidInputException("대상 ID 목록이 비어있습니다.")
            targetIds.any { it <= 0 } ->
                throw InvalidInputException("잘못된 대상 ID가 포함되어 있습니다.")
            targetIds.size > Constants.MAX_TARGET_IDS ->
                throw InvalidInputException("대상 ID는 최대 ${Constants.MAX_TARGET_IDS}개까지 지정 가능합니다.")
        }
    }
}
