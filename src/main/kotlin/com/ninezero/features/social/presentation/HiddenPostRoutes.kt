package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.HiddenPostService
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.hiddenPostRoutes() {
    route(Constants.Endpoints.HIDDEN_POSTS) {
        val hiddenPostService by inject<HiddenPostService>()

        authenticate("jwt") {
            post("/{postId}", {
                summary = "포스트 숨김"
                tags("HiddenPosts")
                request {
                    pathParameter<Int>("postId") {
                        description = "포스트 ID"
                        required = true
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@post

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@post

                val response = hiddenPostService.hidePost(userId, postId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
