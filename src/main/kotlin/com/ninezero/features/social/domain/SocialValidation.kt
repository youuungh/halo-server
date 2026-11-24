package com.ninezero.features.social.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidCommentContentException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.InvalidPostContentException

/** 포스트·댓글 입력 검증 */
object SocialValidation {
    fun validatePostContent(content: String) {
        when {
            content.isBlank() ->
                throw InvalidPostContentException("포스트 내용은 필수입니다.")
            content.length > Constants.Social.MAX_POST_CONTENT_LENGTH ->
                throw InvalidPostContentException("포스트 내용은 ${Constants.Social.MAX_POST_CONTENT_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateCommentContent(content: String) {
        when {
            content.isBlank() ->
                throw InvalidCommentContentException("댓글 내용은 필수입니다.")
            content.length > Constants.Social.MAX_COMMENT_CONTENT_LENGTH ->
                throw InvalidCommentContentException("댓글 내용은 ${Constants.Social.MAX_COMMENT_CONTENT_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    /** 게시 요청 식별자, 없으면 통과 */
    fun validateClientRequestId(clientRequestId: String?) {
        if (clientRequestId != null &&
            (clientRequestId.isBlank() || clientRequestId.length > Constants.Social.MAX_CLIENT_REQUEST_ID_LENGTH)
        ) {
            throw InvalidInputException(Errors.Common.INVALID_CLIENT_REQUEST_ID)
        }
    }
}
