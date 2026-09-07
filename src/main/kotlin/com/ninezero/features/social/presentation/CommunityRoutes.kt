package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.CommunityPostSortType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.CommunityService
import com.ninezero.features.social.presentation.models.request.CommunityPostRequest
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

fun Route.communityRoutes() {
    route(Constants.Endpoints.COMMUNITY) {
        val communityService by inject<CommunityService>()

        authenticate("jwt", optional = true) {
            get("/creator/{creatorId}", {
                summary = "커뮤니티 글 목록 조회 (공개)"
                tags("Community")
                request {
                    pathParameter<Int>("creatorId") {
                        description = "크리에이터 ID"
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
                        description = "정렬 방식: LATEST (최신순, 기본값), POPULAR (좋아요순), MOST_VIEWED (조회수순), OLDEST (오래된순)"
                        required = false
                    }
                }
                apiResponse()
            }) {
                val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()
                val sort = call.request.queryParameters["sort"]?.let {
                    runCatching { CommunityPostSortType.valueOf(it.uppercase()) }.getOrNull()
                } ?: CommunityPostSortType.LATEST

                val response = communityService.getCommunityPosts(
                    creatorId = creatorId,
                    currentUserId = currentUserId,
                    page = page,
                    limit = limit,
                    sort = sort
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        authenticate("jwt") {
            post("/creator/{creatorId}", {
                summary = "커뮤니티 글 작성 (Followers, Creator)"
                tags("Community")
                request {
                    pathParameter<Int>("creatorId") {
                        description = "크리에이터 ID"
                        required = true
                    }
                    body<CommunityPostRequest> {
                        description = "커뮤니티 글 정보"
                        required = true
                        example("default") {
                            value = CommunityPostRequest(
                                content = "안녕하세요! 팬입니다~"
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "커뮤니티 글 작성 성공"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터 또는 팔로워만 작성 가능"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                    ?: return@post

                val request = call.receive<CommunityPostRequest>()
                val response = communityService.createCommunityPost(userId, creatorId, request)
                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse.success(response, Messages.Social.COMMUNITY_POST_CREATED)
                )
            }

            put("/{postId}", {
                summary = "커뮤니티 글 수정 (Owner)"
                tags("Community")
                request {
                    pathParameter<Int>("postId") {
                        description = "게시글 ID"
                        required = true
                    }
                    queryParameter<Int>("creatorId") {
                        description = "크리에이터 ID (Multipart 요청 시 필수)"
                        required = false
                    }
                    body<CommunityPostRequest> {
                        description = "수정할 내용 (JSON)"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "커뮤니티 글 수정 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 요청"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "작성자 본인만 수정 가능"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "게시글 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@put

                val contentType = call.request.contentType()

                val response = if (contentType.match(ContentType.MultiPart.FormData)) {
                    val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                        ?: return@put

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

                    communityService.updateCommunityWithMedia(
                        postId = postId,
                        userId = userId,
                        creatorId = creatorId,
                        content = content,
                        keepMediaIds = if (keepMediaIds.isEmpty()) null else keepMediaIds,
                        newFiles = if (newFiles.isEmpty()) null else newFiles,
                        newContentTypes = if (newContentTypes.isEmpty()) null else newContentTypes,
                        reorder = if (reorder.isEmpty()) null else reorder
                    )
                } else {
                    val request = call.receive<CommunityPostRequest>()
                    communityService.updateCommunityPost(postId, userId, request)
                }

                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Social.POST_UPDATED))
            }

            delete("/{postId}", {
                summary = "커뮤니티 글 삭제 (Owner, Creator, Admin)"
                tags("Community")
                request {
                    pathParameter<Int>("postId") {
                        description = "게시글 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "삭제 성공"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "권한 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@delete

                communityService.deleteCommunityPost(postId, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            put("/{postId}/pin", {
                summary = "커뮤니티 공지 고정/해제 (Creator, Admin)"
                tags("Community")
                request {
                    pathParameter<Int>("postId") {
                        description = "게시글 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터 또는 관리자만 고정 가능"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@put

                val response = communityService.togglePinCommunityPost(postId, userId)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        response,
                        if (response["isPinned"] == true) Messages.Social.POST_PINNED else Messages.Social.POST_UNPINNED
                    )
                )
            }

            post("/creator/{creatorId}/{postId}/media", {
                summary = "커뮤니티 이미지 업로드 (Followers, Creator)"
                tags("Community")
                request {
                    pathParameter<Int>("creatorId") {
                        description = "크리에이터 ID"
                        required = true
                    }
                    pathParameter<Int>("postId") {
                        description = "게시글 ID"
                        required = true
                    }
                    body<ByteArray> {
                        description = "Multipart form data - 이미지 파일만"
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "이미지 업로드 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "파일이 없거나 이미지가 아님"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                    ?: return@post

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@post

                val mediaType = PostType.IMAGE

                val (mediaFiles, contentTypes) = call.receiveFileParts()

                if (mediaFiles.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>(Errors.File.NO_MEDIA_FILES)
                    )
                }

                val response = communityService.uploadCommunityMedia(
                    postId = postId,
                    userId = userId,
                    creatorId = creatorId,
                    mediaFiles = mediaFiles,
                    contentTypes = contentTypes,
                    mediaType = mediaType
                )

                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Common.MEDIA_UPLOADED))
            }
        }
    }
}
