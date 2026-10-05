package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.UserPostSortType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.PaginatedResponse
import com.ninezero.core.common.util.apiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.getIntParam
import com.ninezero.core.common.util.getOptionalEnumParam
import com.ninezero.core.common.util.getOptionalStringParam
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.core.common.util.getUserIdOrNull
import com.ninezero.core.common.util.receiveFileParts
import com.ninezero.core.common.util.requireAdminId
import com.ninezero.core.common.util.requireUserId
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.social.domain.PostMediaService
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.social.domain.parseMediaItems
import com.ninezero.features.social.presentation.models.request.PostRequest
import com.ninezero.features.social.presentation.models.request.UpdatePostRequest
import com.ninezero.features.social.presentation.models.response.AdminPostResponse
import com.ninezero.features.social.presentation.models.response.PostResponse
import com.ninezero.features.upload.presentation.models.request.VideoUploadPrepareRequest
import com.ninezero.features.upload.presentation.models.response.UploadPrepareResponse
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.postRoutes() {
    route(Constants.Endpoints.POSTS) {
        val postService by inject<PostService>()
        val postMediaService by inject<PostMediaService>()

        authenticate("jwt", optional = true) {
            get({
                summary = "사용자 포스트 목록 조회 (공개)"
                tags("Posts")
                request {
                    queryParameter<String>("cursor") {
                        description = "다음 페이지 커서 (응답의 nextCursor, 생략 시 처음부터)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<Int>("userId") {
                        description = "사용자 ID"
                        required = true
                    }
                    queryParameter<String>("sort") {
                        description = "정렬 방식: LATEST (최신순, 기본값), OLDEST (오래된순)"
                        required = false
                    }
                    queryParameter<String>("contextType") {
                        description = "포스트 타입 필터: GENERAL, CREATOR_FEED, COMMUNITY"
                        required = false
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "사용자 ID 누락 또는 형식 오류"
                    }
                }
            }) {
                val userId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get

                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()
                val sort = call.getOptionalEnumParam<UserPostSortType>("sort") ?: UserPostSortType.LATEST
                val contextType = call.getOptionalEnumParam<PostContextType>("contextType")

                val response = postService.getUserPosts(userId, currentUserId, cursor, limit, sort, contextType)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{postId}", {
                summary = "포스트 상세 조회 (공개)"
                tags("Posts")
                request {
                    pathParameter<Int>("postId") {
                        description = "포스트 ID"
                        required = true
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
                val currentUserId = call.getUserIdOrNull()

                val response = postService.getPostById(postId, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/creator/{creatorId}/pinned", {
                summary = "크리에이터 고정 포스트 조회 (공개)"
                tags("Posts")
                request {
                    pathParameter<Int>("creatorId") {
                        description = "크리에이터 ID"
                        required = true
                    }
                }
                apiResponse()
            }) {
                val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                    ?: return@get
                val currentUserId = call.getUserIdOrNull()

                val response = postService.getPinnedPostsInFeed(creatorId, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/creator/{creatorId}/sections", {
                summary = "크리에이터 프로필 섹션 조회 (공개)"
                tags("Posts")
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
                }
                apiResponse()
            }) {
                val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()

                val response = postService.getCreatorProfileSections(
                    creatorId = creatorId,
                    currentUserId = currentUserId,
                    page = page,
                    limit = limit
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/tag/{tagId}", {
                summary = "태그별 포스트 조회 (공개)"
                tags("Posts")
                request {
                    pathParameter<Int>("tagId") {
                        description = "태그 ID"
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
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "태그 없음"
                    }
                }
            }) {
                val tagId = call.getRequiredIntParam("tagId", Errors.Tag.INVALID_TAG_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()

                val response = postService.getPostsByTag(
                    tagId = tagId,
                    currentUserId = currentUserId,
                    page = page,
                    limit = limit
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        authenticate("jwt") {
            post({
                summary = "포스트 작성"
                tags("Posts")
                description = "미디어는 초안 ID 목록(mediaDraftIds)으로 함께 붙임, 텍스트 글은 비움"
                request {
                    body<PostRequest> {
                        description = "포스트 정보"
                        required = true
                        example("텍스트 포스트") {
                            value = PostRequest(
                                content = "안녕하세요! 첫 포스트입니다.",
                                postType = PostType.TEXT
                            )
                        }
                        example("이미지 포스트") {
                            value = PostRequest(
                                content = "새로운 작품을 공유합니다!",
                                postType = PostType.IMAGE
                            )
                        }
                        example("상품 포스트") {
                            value = PostRequest(
                                content = "신상품 출시!",
                                postType = PostType.PRODUCT,
                                productId = 1
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "포스트 생성 성공"
                        body<ApiResponse<PostResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 요청 데이터"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<PostRequest>()
                val response = postService.createPost(userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Social.POST_CREATED))
            }

            put("/{postId}", {
                summary = "포스트 수정"
                tags("Posts")
                description = "JSON 또는 multipart"
                request {
                    pathParameter<Int>("postId") {
                        description = "포스트 ID"
                        required = true
                    }
                    body<UpdatePostRequest> {
                        description = "수정할 포스트 정보 (JSON)"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "본인 포스트만 수정 가능"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "포스트 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@put

                val contentType = call.request.contentType()

                val response = if (contentType.match(ContentType.MultiPart.FormData)) {
                    var content: String? = null
                    val keepMediaIds = mutableListOf<Int>()
                    val reorder = mutableListOf<Int>()
                    var items: String? = null

                    val (newFiles, newContentTypes) = call.receiveFileParts(StorageConfig.FileSizeLimit.POST) { name, value ->
                        when (name) {
                            "content" -> content = value
                            "items" -> items = value
                            "keepMediaIds" -> keepMediaIds.addAll(value.split(",").mapNotNull { it.trim().toIntOrNull() })
                            "reorder" -> reorder.addAll(value.split(",").mapNotNull { it.trim().toIntOrNull() })
                        }
                    }

                    postMediaService.updatePostWithMedia(
                        postId = postId,
                        userId = userId,
                        content = content,
                        keepMediaIds = if (keepMediaIds.isEmpty()) null else keepMediaIds,
                        newFiles = if (newFiles.isEmpty()) null else newFiles,
                        newContentTypes = if (newContentTypes.isEmpty()) null else newContentTypes,
                        reorder = if (reorder.isEmpty()) null else reorder,
                        items = items?.let(::parseMediaItems)
                    )
                } else {
                    val request = call.receive<UpdatePostRequest>()
                    postService.updatePost(postId, userId, request)
                }

                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Social.POST_UPDATED))
            }

            post("/{postId}/uploads", {
                summary = "포스트 영상 업로드 준비"
                tags("Posts")
                description = "서명된 업로드 URL 발급, 앱이 이 URL로 직접 올린 뒤 수정 요청 items의 uploadId로 붙임"
                request {
                    pathParameter<Int>("postId") {
                        description = "포스트 ID"
                        required = true
                    }
                    body<VideoUploadPrepareRequest> {
                        description = "영상 형식, 크기"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "업로드 URL 발급 성공"
                        body<ApiResponse<UploadPrepareResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "크기 초과 또는 지원하지 않는 형식"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "본인 포스트만 가능"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@post

                val request = call.receive<VideoUploadPrepareRequest>()
                val response = postMediaService.preparePostVideoUpload(postId, userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response))
            }

            delete("/{postId}", {
                summary = "포스트 삭제"
                tags("Posts")
                request {
                    pathParameter<Int>("postId") {
                        description = "포스트 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "포스트 삭제 성공"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "본인 포스트만 삭제 가능"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "포스트 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@delete

                postService.deletePost(postId, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            put("/{postId}/pin", {
                summary = "포스트 피드 고정/해제 (Creator)"
                tags("Posts")
                request {
                    pathParameter<Int>("postId") {
                        description = "포스트 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터만 고정 가능"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@put

                val response = postService.togglePinPostInFeed(postId, userId)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        response,
                        if (response.isPinned) Messages.Social.POST_PINNED else Messages.Social.POST_UNPINNED
                    )
                )
            }

            get("/my", {
                summary = "내 포스트 목록 조회"
                tags("Posts")
                request {
                    queryParameter<String>("cursor") {
                        description = "다음 페이지 커서 (응답의 nextCursor, 생략 시 처음부터)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<String>("sort") {
                        description = "정렬 방식: LATEST (최신순, 기본값), OLDEST (오래된순)"
                        required = false
                    }
                    queryParameter<String>("contextType") {
                        description = "포스트 타입 필터: GENERAL, CREATOR_FEED, COMMUNITY"
                        required = false
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val sort = call.getOptionalEnumParam<UserPostSortType>("sort") ?: UserPostSortType.LATEST
                val contextType = call.getOptionalEnumParam<PostContextType>("contextType")

                val response = postService.getUserPosts(userId, userId, cursor, limit, sort, contextType)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/admin", {
                summary = "전체 포스트 목록 (Admin)"
                tags("Posts")
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
                response {
                    code(HttpStatusCode.OK) {
                        description = "전체 포스트 목록 조회 성공"
                        body<ApiResponse<PaginatedResponse<AdminPostResponse>>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "권한 없음"
                    }
                }
            }) {
                call.requireAdminId() ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = postService.getAdminPosts(page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
