package com.ninezero.features.user.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.InvalidBioException
import com.ninezero.core.common.exception.InvalidDisplayNameException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.InvalidSearchQueryException
import com.ninezero.core.common.exception.InvalidUsernameException
import com.ninezero.core.common.util.CommonValidation

/** 프로필·주소·크리에이터 신청·사용자 검색 입력 검증 */
object UserValidation {
    private val USERNAME_REGEX = Regex("^[a-z0-9_]+$")

    /** 대소문자 구분 없는 username 저장 형태 */
    fun normalizeUsername(username: String): String = username.trim().lowercase()

    fun validateUsername(username: String) {
        when {
            username.isBlank() ->
                throw InvalidUsernameException("사용자명은 필수입니다.")
            username.length < Constants.User.MIN_USERNAME_LENGTH ->
                throw InvalidUsernameException("사용자명은 최소 ${Constants.User.MIN_USERNAME_LENGTH}자 이상이어야 합니다.")
            username.length > Constants.User.MAX_USERNAME_LENGTH ->
                throw InvalidUsernameException("사용자명은 ${Constants.User.MAX_USERNAME_LENGTH}자를 초과할 수 없습니다.")
            !USERNAME_REGEX.matches(username) ->
                throw InvalidUsernameException("사용자명은 영문, 숫자, 밑줄(_)만 사용할 수 있습니다.")
        }
    }

    fun validateDisplayName(displayName: String?) {
        if (displayName == null) return

        when {
            displayName.isBlank() ->
                throw InvalidDisplayNameException("표시 이름은 비워 둘 수 없습니다.")
            displayName.length > Constants.User.MAX_DISPLAY_NAME_LENGTH ->
                throw InvalidDisplayNameException("표시 이름은 ${Constants.User.MAX_DISPLAY_NAME_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateBio(bio: String?) {
        if (bio == null) return

        when {
            bio.length > Constants.User.MAX_BIO_LENGTH ->
                throw InvalidBioException("소개는 ${Constants.User.MAX_BIO_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateLocation(location: String?) {
        if (location == null) return

        when {
            location.length > Constants.User.MAX_LOCATION_LENGTH ->
                throw InvalidInputException("위치는 ${Constants.User.MAX_LOCATION_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    /** http·https·// URL만 통과 */
    fun sanitizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null

        val sanitized = url.trim()

        val safeProtocols = listOf("http://", "https://", "//")
        val isSafe = safeProtocols.any { sanitized.startsWith(it, ignoreCase = true) }

        return if (isSafe) sanitized else null  // javascript: 등 나머지는 null
    }

    fun validateWebsite(website: String?) {
        if (website.isNullOrBlank()) return  // 빈 값은 지우기

        when {
            website.length > Constants.User.MAX_WEBSITE_LENGTH ->
                throw InvalidInputException("웹사이트 URL은 ${Constants.User.MAX_WEBSITE_LENGTH}자를 초과할 수 없습니다.")
            !website.startsWith("http://") && !website.startsWith("https://") ->
                throw InvalidInputException("웹사이트 URL은 http:// 또는 https://로 시작해야 합니다.")
        }
    }

    fun validateCreatorApplicationReason(reason: String) {
        when {
            reason.isBlank() ->
                throw InvalidInputException("신청 사유는 필수입니다.")
            reason.length < Constants.User.MIN_CREATOR_APPLICATION_REASON_LENGTH ->
                throw InvalidInputException("신청 사유는 최소 ${Constants.User.MIN_CREATOR_APPLICATION_REASON_LENGTH}자 이상 입력해주세요.")
            reason.length > Constants.User.MAX_CREATOR_APPLICATION_REASON_LENGTH ->
                throw InvalidInputException("신청 사유는 최대 ${Constants.User.MAX_CREATOR_APPLICATION_REASON_LENGTH}자까지 입력 가능합니다.")
        }
    }

    fun validateRejectionReason(reason: String) {
        when {
            reason.isBlank() ->
                throw InvalidInputException("거절 사유를 입력해주세요.")
            reason.length < Constants.User.MIN_REJECTION_REASON_LENGTH ->
                throw InvalidInputException("거절 사유는 최소 ${Constants.User.MIN_REJECTION_REASON_LENGTH}자 이상 입력해주세요.")
            reason.length > Constants.User.MAX_REJECTION_REASON_LENGTH ->
                throw InvalidInputException("거절 사유는 최대 ${Constants.User.MAX_REJECTION_REASON_LENGTH}자까지 입력 가능합니다.")
        }
    }

    fun validateRecipientName(name: String) {
        when {
            name.isBlank() ->
                throw InvalidInputException("수취인 이름은 필수입니다.")
            name.length > Constants.Address.MAX_RECIPIENT_NAME_LENGTH ->
                throw InvalidInputException("수취인 이름은 ${Constants.Address.MAX_RECIPIENT_NAME_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateRecipientPhone(phone: String) {
        when {
            phone.isBlank() ->
                throw InvalidInputException("연락처는 필수입니다.")
            phone.length > Constants.Address.MAX_RECIPIENT_PHONE_LENGTH ->
                throw InvalidInputException("연락처는 ${Constants.Address.MAX_RECIPIENT_PHONE_LENGTH}자를 초과할 수 없습니다.")
            !phone.matches(CommonValidation.PHONE_REGEX) ->
                throw InvalidInputException("올바른 전화번호 형식이 아닙니다. (예: 010-1234-5678)")
        }
    }

    fun validateZipCode(zipCode: String) {
        when {
            zipCode.isBlank() ->
                throw InvalidInputException("우편번호는 필수입니다.")
            zipCode.length > Constants.Address.MAX_ZIP_CODE_LENGTH ->
                throw InvalidInputException("우편번호는 ${Constants.Address.MAX_ZIP_CODE_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateAddress(address: String) {
        when {
            address.isBlank() ->
                throw InvalidInputException("주소는 필수입니다.")
            address.length > Constants.Address.MAX_ADDRESS_LENGTH ->
                throw InvalidInputException("주소는 ${Constants.Address.MAX_ADDRESS_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateAddressDetail(detail: String?) {
        if (detail.isNullOrBlank()) return

        when {
            detail.length > Constants.Address.MAX_ADDRESS_DETAIL_LENGTH ->
                throw InvalidInputException("상세 주소는 ${Constants.Address.MAX_ADDRESS_DETAIL_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateAddressMemo(memo: String?) {
        if (memo.isNullOrBlank()) return

        when {
            memo.length > Constants.Address.MAX_MEMO_LENGTH ->
                throw InvalidInputException("배송 메모는 ${Constants.Address.MAX_MEMO_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateSearchQuery(query: String) {
        when {
            query.isBlank() ->
                throw InvalidSearchQueryException("검색어를 입력해주세요.")
        }
    }
}
