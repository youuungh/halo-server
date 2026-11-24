package com.ninezero.features.chat.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.InvalidSearchQueryException

/** 채팅 메시지·검색어 검증 */
object ChatValidation {
    fun validateChatMessage(content: String?, attachmentCount: Int = 0) {
        if (content.isNullOrBlank() && attachmentCount == 0) {
            throw InvalidInputException("메시지 내용 또는 미디어가 필요합니다.")
        }
        content?.let {
            if (it.length > Constants.Chat.MAX_MESSAGE_LENGTH) {
                throw InvalidInputException("메시지는 ${Constants.Chat.MAX_MESSAGE_LENGTH}자를 초과할 수 없습니다.")
            }
        }
        if (attachmentCount > Constants.Chat.MAX_MEDIA_PER_MESSAGE) {
            throw InvalidInputException("미디어는 최대 ${Constants.Chat.MAX_MEDIA_PER_MESSAGE}개까지 첨부 가능합니다.")
        }
    }

    fun validateClientTempId(clientTempId: String?) {
        if (clientTempId != null && (clientTempId.isBlank() || clientTempId.length > Constants.Chat.MAX_CLIENT_TEMP_ID_LENGTH)) {
            throw InvalidInputException(Errors.Chat.INVALID_CLIENT_TEMP_ID)
        }
    }

    fun validateMessageSearchQuery(query: String) {
        when {
            query.isBlank() || query.length < Constants.Chat.MIN_SEARCH_QUERY_LENGTH ->
                throw InvalidSearchQueryException("검색어는 최소 ${Constants.Chat.MIN_SEARCH_QUERY_LENGTH}자 이상이어야 합니다.")
        }
    }
}
