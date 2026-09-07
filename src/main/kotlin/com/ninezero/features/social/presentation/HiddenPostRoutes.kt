package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.HiddenPostService
import io.github.smiley4.ktoropenapi.get
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
                summary = "포스트 숨김 토글"
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

                val response = hiddenPostService.toggleHidePost(userId, postId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{postId}/status", {
                summary = "포스트 숨김 상태 확인"
                tags("HiddenPosts")
                request {
                    pathParameter<Int>("postId") {
                        description = "포스트 ID"
                        required = true
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@get

                val response = hiddenPostService.isHidden(userId, postId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my", {
                summary = "숨긴 포스트 목록 조회"
                tags("HiddenPosts")
                request {
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = hiddenPostService.getUserHiddenPosts(userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my/count", {
                summary = "숨긴 포스트 수 조회"
                tags("HiddenPosts")
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = hiddenPostService.getUserHiddenPostCount(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
