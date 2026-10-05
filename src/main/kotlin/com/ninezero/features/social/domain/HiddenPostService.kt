package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.features.social.data.HiddenPostRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.presentation.models.response.HiddenPostToggleResponse

class HiddenPostService(
    private val hiddenPostRepository: HiddenPostRepository,
    private val postRepository: PostRepository,
    private val cacheService: CacheService
) {

    /** 관심없음 처리 */
    suspend fun hidePost(userId: Int, postId: Int): HiddenPostToggleResponse {
        val response = query {
            postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            hiddenPostRepository.createHiddenPost(userId, postId)  // 이미 숨김이면 그대로 유지

            HiddenPostToggleResponse(
                isHidden = true,
                message = Messages.Social.HIDE_POST_SUCCESS
            )
        }

        // 숨긴 글이 남은 본인 탐색·커뮤니티 캐시
        cacheService.deletePattern(CacheKeys.Patterns.userScoped(userId))

        return response
    }
}
