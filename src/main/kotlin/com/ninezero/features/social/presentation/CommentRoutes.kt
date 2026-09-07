package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.CommentSortType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.CommentService
import com.ninezero.features.social.presentation.models.request.CommentRequest
import com.ninezero.features.social.presentation.models.request.UpdateCommentRequest
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import org.koin.ktor.ext.inject

fun Route.commentRoutes() {
    route(Constants.Endpoints.COMMENTS) {
        val commentService by inject<CommentService>()

        authenticate("jwt", optional = true) {
            get({
                summary = "포스트 댓글 목록 조회 (공개)"
                tags("Comments")
                request {
                    queryParameter<Int>("postId") {
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
                    queryParameter<String>("sort") {
                        description = "정렬 방식: POPULAR (인기순, 기본값), LATEST (최신순)"
                        required = false
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "포스트 없음"
                    }
                }
            }) {
                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()
                val sort = call.request.queryParameters["sort"]?.let {
                    runCatching { CommentSortType.valueOf(it.uppercase()) }.getOrNull()
                } ?: CommentSortType.POPULAR

                val response = commentService.getPostComments(postId, currentUserId, page, limit, sort)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{commentId}", {
                summary = "댓글 조회 (공개)"
                tags("Comments")
                request {
                    pathParameter<Int>("commentId") {
                        description = "댓글 ID"
                        required = true
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "댓글 없음"
                    }
                }
            }) {
                val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                    ?: return@get

                val currentUserId = call.getUserIdOrNull()
                val response = commentService.getCommentById(commentId, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{commentId}/thread", {
                summary = "댓글 조상 체인 조회 (공개)"
                tags("Comments")
                description = "딥링크 진입용, root→target 순"
                request {
                    pathParameter<Int>("commentId") {
                        description = "댓글 ID"
                        required = true
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "댓글 없음"
                    }
                }
            }) {
                val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                    ?: return@get

                val currentUserId = call.getUserIdOrNull()
                val response = commentService.getCommentThread(commentId, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{commentId}/replies", {
                summary = "대댓글 목록 조회 (공개)"
                tags("Comments")
                request {
                    pathParameter<Int>("commentId") {
                        description = "부모 댓글 ID"
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
                    queryParameter<String>("sort") {
                        description = "정렬 방식: POPULAR (인기순, 기본값), LATEST (최신순)"
                        required = false
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "댓글 없음"
                    }
                }
            }) {
                val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()
                val sort = call.request.queryParameters["sort"]?.let {
                    runCatching { CommentSortType.valueOf(it.uppercase()) }.getOrNull()
                } ?: CommentSortType.POPULAR

                val response = commentService.getCommentReplies(commentId, currentUserId, page, limit, sort)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        authenticate("jwt") {
            post({
                summary = "댓글 작성"
                tags("Comments")
                description = "대댓글 포함"
                request {
                    body<CommentRequest> {
                        description = "댓글 정보"
                        required = true
                        example("일반 댓글") {
                            value = CommentRequest(
                                postId = 1,
                                content = "좋은 게시글이네요!"
                            )
                        }
                        example("대댓글") {
                            value = CommentRequest(
                                postId = 1,
                                content = "동의합니다!",
                                parentCommentId = 10
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "댓글 작성 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 요청 데이터"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<CommentRequest>()
                val response = commentService.createComment(
                    userId = userId,
                    postId = request.postId,
                    content = request.content,
                    parentCommentId = request.parentCommentId
                )
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Social.COMMENT_CREATED))
            }

            put("/{commentId}", {
                summary = "댓글 수정"
                tags("Comments")
                request {
                    pathParameter<Int>("commentId") {
                        description = "댓글 ID"
                        required = true
                    }
                    body<UpdateCommentRequest> {
                        description = "수정할 내용"
                        required = true
                        example("default") {
                            value = UpdateCommentRequest(
                                content = "수정된 댓글 내용입니다."
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "본인 댓글만 수정 가능"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "댓글 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                    ?: return@put

                val contentType = call.request.contentType()
                val response = if (contentType.match(ContentType.MultiPart.FormData)) {
                    val multipart = call.receiveMultipart()
                    var content: String? = null
                    val keepMediaIds = mutableListOf<Int>()
                    val reorder = mutableListOf<Int>()
                    val newFiles = mutableListOf<ByteArray>()
                    val newContentTypes = mutableListOf<String>()

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                when (part.name) {
                                    "content" -> content = part.value
                                    "keepMediaIds" -> {
                                        keepMediaIds.addAll(
                                            part.value.split(",")
                                                .mapNotNull { it.trim().toIntOrNull() }
                                        )
                                    }

                                    "reorder" -> {
                                        reorder.addAll(
                                            part.value.split(",")
                                                .mapNotNull { it.trim().toIntOrNull() }
                                        )
                                    }
                                }
                            }

                            is PartData.FileItem -> {
                                val ct = part.contentType?.toString() ?: "image/jpeg"
                                newContentTypes.add(ct)

                                val channel = part.provider()
                                val packet = channel.readRemaining()
                                newFiles.add(packet.readByteArray())
                            }

                            else -> Unit
                        }
                        part.dispose()
                    }

                    commentService.updateCommentWithMedia(
                        commentId = commentId,
                        userId = userId,
                        content = content,
                        keepMediaIds = if (keepMediaIds.isEmpty()) null else keepMediaIds,
                        newFiles = if (newFiles.isEmpty()) null else newFiles,
                        newContentTypes = if (newContentTypes.isEmpty()) null else newContentTypes,
                        reorder = if (reorder.isEmpty()) null else reorder
                    )
                } else {
                    val request = call.receive<UpdateCommentRequest>()
                    commentService.updateComment(commentId, userId, request.content)
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Social.COMMENT_UPDATED))
            }

            delete("/{commentId}", {
                summary = "댓글 삭제"
                tags("Comments")
                request {
                    pathParameter<Int>("commentId") {
                        description = "댓글 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "댓글 삭제 성공"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "본인 댓글만 삭제 가능"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "댓글 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                    ?: return@delete

                commentService.deleteComment(commentId, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{commentId}/media", {
                summary = "댓글 미디어 업로드"
                tags("Comments")
                description = "이미지·비디오"
                request {
                    pathParameter<Int>("commentId") {
                        description = "댓글 ID"
                        required = true
                    }
                    queryParameter<String>("type") {
                        description = "미디어 타입 (IMAGE/VIDEO, 기본값: IMAGE)"
                        required = false
                    }
                    body<ByteArray> {
                        description = "Multipart form data - 파일 업로드"
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "미디어 업로드 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "파일이 없거나 잘못된 미디어 타입"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val commentId = call.getRequiredIntParam("commentId", Errors.Social.Comment.INVALID_COMMENT_ID)
                    ?: return@post

                val mediaTypeStr = call.getStringParam("type", "IMAGE").uppercase()
                val mediaType = try {
                    PostType.valueOf(mediaTypeStr)
                } catch (_: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>(Errors.Social.Post.INVALID_MEDIA_TYPE)
                    )
                }

                val (mediaFiles, contentTypes) = call.receiveFileParts()

                if (mediaFiles.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>(Errors.File.NO_MEDIA_FILES)
                    )
                }

                val response = commentService.uploadCommentMedia(commentId, userId, mediaFiles, contentTypes, mediaType)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Common.MEDIA_UPLOADED))
            }
        }
    }
}
