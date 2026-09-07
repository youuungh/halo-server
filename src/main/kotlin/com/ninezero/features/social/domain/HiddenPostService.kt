package com.ninezero.features.social.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.HiddenPostOperationFailedException
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.features.social.data.HiddenPostRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.presentation.models.response.HiddenPostListResponse
import com.ninezero.features.social.presentation.models.response.HiddenPostToggleResponse

class HiddenPostService(
    private val hiddenPostRepository: HiddenPostRepository,
    private val postRepository: PostRepository
) {

    suspend fun toggleHidePost(userId: Int, postId: Int): HiddenPostToggleResponse {
        return query {
            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            val isCurrentlyHidden = hiddenPostRepository.isHidden(userId, postId)

            if (isCurrentlyHidden) {
                val deleted = hiddenPostRepository.deleteHiddenPost(userId, postId)
                if (!deleted) {
                    throw HiddenPostOperationFailedException(Errors.Social.HiddenPost.UNHIDE_FAILED)
                }

                HiddenPostToggleResponse(
                    isHidden = false,
                    message = Messages.Social.UNHIDE_POST_SUCCESS
                )
            } else {
                hiddenPostRepository.createHiddenPost(userId, postId)
                    ?: throw HiddenPostOperationFailedException(Errors.Social.HiddenPost.HIDE_FAILED)

                HiddenPostToggleResponse(
                    isHidden = true,
                    message = Messages.Social.HIDE_POST_SUCCESS
                )
            }
        }
    }

    suspend fun getUserHiddenPosts(
        userId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): HiddenPostListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val hiddenPostIds = hiddenPostRepository.findUserHiddenPosts(userId, validPage, validLimit)
            val totalCount = hiddenPostRepository.countUserHiddenPosts(userId)

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(hiddenPostIds, pagination)
        }
    }

    suspend fun isHidden(userId: Int, postId: Int): Map<String, Boolean> {
        return query {
            val isHidden = hiddenPostRepository.isHidden(userId, postId)
            mapOf("isHidden" to isHidden)
        }
    }

    suspend fun getUserHiddenPostCount(userId: Int): Map<String, Int> {
        return query {
            val count = hiddenPostRepository.countUserHiddenPosts(userId)
            mapOf("count" to count)
        }
    }
}
