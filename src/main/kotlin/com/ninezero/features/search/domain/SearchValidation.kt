package com.ninezero.features.search.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.InvalidSearchQueryException

/** 통합 검색어 검증 */
object SearchValidation {
    fun validateSearchKeywordLength(keyword: String) {
        when {
            keyword.isBlank() ->
                throw InvalidSearchQueryException("검색어를 입력해주세요.")
            keyword.length > Constants.Search.MAX_KEYWORD_LENGTH ->
                throw InvalidSearchQueryException("검색어는 최대 ${Constants.Search.MAX_KEYWORD_LENGTH}자까지 입력 가능합니다.")
        }
    }
}
