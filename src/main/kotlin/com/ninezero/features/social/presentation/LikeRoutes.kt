package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.LikeType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.LikeService
import com.ninezero.features.social.presentation.models.request.LikeRequest
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.likeRoutes() {
    route(Constants.Endpoints.LIKES) {
        val likeService by inject<LikeService>()

        get("/posts/{postId}/count", {
            summary = "포스트 좋아요 수 조회 (공개)"
            tags("Likes")
            request {
                pathParameter<Int>("postId") {
                    description = "포스트 ID"
                    required = true
                }
            }
            apiResponse()
        }) {
            val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                ?: return@get

            val response = likeService.getPostLikeCount(postId)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        get("/comments/{commentId}/count", {
            summary = "댓글 좋아요 수 조회 (공개)"
            tags("Likes")
            request {
                pathParameter<Int>("commentId") {
                    description = "댓글 ID"
                    required = true
                }
            }
            apiResponse()
        }) {
            val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                ?: return@get

            val response = likeService.getCommentLikeCount(commentId)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        get("/posts/{postId}/users", {
            summary = "포스트 좋아요 사용자 목록 조회 (공개)"
            tags("Likes")
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
            apiResponse()
        }) {
            val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                ?: return@get

            val page = call.getIntParam("page", 1)
            val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

            val response = likeService.getPostLikedUsers(postId, page, limit)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        get("/comments/{commentId}/users", {
            summary = "댓글 좋아요 사용자 목록 조회 (공개)"
            tags("Likes")
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
            apiResponse()
        }) {
            val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                ?: return@get

            val page = call.getIntParam("page", 1)
            val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

            val response = likeService.getCommentLikedUsers(commentId, page, limit)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        authenticate("jwt") {
            post("/toggle", {
                summary = "좋아요 토글"
                tags("Likes")
                description = "포스트 또는 댓글"
                request {
                    body<LikeRequest> {
                        description = "좋아요 대상 정보"
                        required = true
                        example("포스트 좋아요") {
                            value = LikeRequest(
                                targetType = LikeType.POST,
                                targetId = 1
                            )
                        }
                        example("댓글 좋아요") {
                            value = LikeRequest(
                                targetType = LikeType.COMMENT,
                                targetId = 10
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "대상 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<LikeRequest>()
                val response = when (request.targetType) {
                    LikeType.POST -> likeService.togglePostLike(userId, request.targetId)
                    LikeType.COMMENT -> likeService.toggleCommentLike(userId, request.targetId)
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

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

            get("/posts/{postId}/status", {
                summary = "포스트 좋아요 상태 확인"
                tags("Likes")
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

                val response = likeService.isPostLiked(userId, postId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/comments/{commentId}/status", {
                summary = "댓글 좋아요 상태 확인"
                tags("Likes")
                request {
                    pathParameter<Int>("commentId") {
                        description = "댓글 ID"
                        required = true
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                    ?: return@get

                val response = likeService.isCommentLiked(userId, commentId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my", {
                summary = "내가 좋아요한 항목 목록 조회"
                tags("Likes")
                description = "포스트·댓글 데이터 포함"
                request {
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
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

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val targetType = call.request.queryParameters["targetType"]?.let {
                    try { LikeType.valueOf(it) } catch (_: Exception) { null }
                }

                val response = likeService.getUserLikedItems(userId, page, limit, targetType)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my/posts", {
                summary = "내가 좋아요한 포스트 ID 목록 조회"
                tags("Likes")
                description = "레거시"
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

                val response = likeService.getUserLikedPosts(userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/users/{userId}/posts", {
                summary = "사용자 좋아요 포스트 목록 조회 (공개)"
                tags("Likes")
                request {
                    pathParameter<Int>("userId") {
                        description = "사용자 ID"
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
                apiResponse()
            }) {
                val targetUserId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = likeService.getUserLikedPosts(targetUserId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
