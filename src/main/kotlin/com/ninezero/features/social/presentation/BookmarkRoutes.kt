package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.BookmarkService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.bookmarkRoutes() {
    route(Constants.Endpoints.BOOKMARKS) {
        val bookmarkService by inject<BookmarkService>()

        authenticate("jwt") {
            post("/posts/{postId}", {
                summary = "포스트 북마크 토글"
                tags("Bookmarks")
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

                val response = bookmarkService.togglePostBookmark(userId, postId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/comments/{commentId}", {
                summary = "댓글 북마크 토글"
                tags("Bookmarks")
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

                val response = bookmarkService.toggleCommentBookmark(userId, commentId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my", {
                summary = "내 북마크 목록 조회"
                tags("Bookmarks")
                description = "포스트·댓글 통합, targetType 필터"
                request {
                    queryParameter<String>("targetType") {
                        description = "필터링할 타입 (POST, COMMENT). 미지정 시 전체 조회"
                        required = false
                    }
                    queryParameter<String>("cursor") {
                        description = "다음 페이지 커서 (응답의 nextCursor, 생략 시 처음부터)"
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

                val targetType = call.getOptionalEnumParam<BookmarkTargetType>("targetType")
                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = bookmarkService.getUserBookmarks(userId, cursor, limit, targetType)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
