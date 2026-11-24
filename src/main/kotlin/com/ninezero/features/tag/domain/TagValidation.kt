package com.ninezero.features.tag.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException

/** 태그 이름 검증 */
object TagValidation {
    private val TAG_NAME_REGEX = Regex("^[가-힣a-zA-Z0-9\\s]+$")

    fun validateTagName(tagName: String?) {
        when {
            tagName.isNullOrBlank() ->
                throw InvalidInputException(Errors.Tag.TAG_NAME_REQUIRED)
            tagName.length > Constants.Social.MAX_TAG_NAME_LENGTH ->
                throw InvalidInputException(Errors.Tag.TAG_NAME_TOO_LONG)
            !tagName.matches(TAG_NAME_REGEX) ->
                throw InvalidInputException(Errors.Tag.TAG_NAME_INVALID)
        }
    }
}
