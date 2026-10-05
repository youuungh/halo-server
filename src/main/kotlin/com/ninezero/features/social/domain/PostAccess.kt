package com.ninezero.features.social.domain

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.features.social.data.PostRepository

/** 작성자 본인 글인지 확인 */
internal suspend fun requireOwnedPost(
    postRepository: PostRepository,
    postId: Int,
    userId: Int,
    errorMessage: String
): PostDao = postRepository.findPostById(postId)
    ?.also { post ->
        if (post.userId != userId) {
            throw ForbiddenException(errorMessage)
        }
    }
    ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)
