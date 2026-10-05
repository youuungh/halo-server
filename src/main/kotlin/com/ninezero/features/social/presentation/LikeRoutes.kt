package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.LikeType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.LikeService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.likeRoutes() {
    route(Constants.Endpoints.LIKES) {
        val likeService by inject<LikeService>()

        authenticate("jwt") {
            post("/posts/{postId}", {
                summary = "포스트 좋아요 토글"
                tags("Likes")
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

                val response = likeService.togglePostLike(userId, postId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/comments/{commentId}", {
                summary = "댓글 좋아요 토글"
                tags("Likes")
                request {
                    pathParameter<Int>("commentId") {
                        description = "댓글 ID"
                        required = true
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@post

                val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                    ?: return@post

                val response = likeService.toggleCommentLike(userId, commentId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/posts/{postId}/users", {
                summary = "포스트 좋아요 사용자 목록 조회"
                tags("Likes")
                description = "차단 관계 사용자 제외, 비밀글은 참여자만"
                request {
                    pathParameter<Int>("postId") {
                        description = "포스트 ID"
                        required = true
                    }
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
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "포스트 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = likeService.getPostLikedUsers(postId, userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/comments/{commentId}/users", {
                summary = "댓글 좋아요 사용자 목록 조회"
                tags("Likes")
                description = "차단 관계 사용자 제외, 비밀글 댓글은 참여자만"
                request {
                    pathParameter<Int>("commentId") {
                        description = "댓글 ID"
                        required = true
                    }
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
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "댓글 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = likeService.getCommentLikedUsers(commentId, userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my", {
                summary = "내가 좋아요한 항목 목록 조회"
                tags("Likes")
                description = "포스트·댓글 데이터 포함"
                request {
                    queryParameter<String>("cursor") {
                        description = "다음 페이지 커서 (응답의 nextCursor, 생략 시 처음부터)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<String>("targetType") {
                        description = "필터 타입 (POST, COMMENT, 생략 시 전체)"
                        required = false
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val targetType = call.getOptionalEnumParam<LikeType>("targetType")

                val response = likeService.getUserLikedItems(userId, cursor, limit, targetType)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
