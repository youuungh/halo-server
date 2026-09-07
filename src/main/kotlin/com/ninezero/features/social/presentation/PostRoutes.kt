package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.UserPostSortType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.receiveFileParts
import com.ninezero.core.common.util.apiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.getIntParam
import com.ninezero.core.common.util.getOptionalIntParam
import com.ninezero.core.common.util.getOptionalStringParam
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.core.common.util.getUserIdOrNull
import com.ninezero.core.common.util.requireAdminId
import com.ninezero.core.common.util.requireUserId
import com.ninezero.features.social.domain.PostService
import com.ninezero.features.social.presentation.models.request.PostRequest
import com.ninezero.features.social.presentation.models.request.UpdatePostRequest
import com.ninezero.features.social.presentation.models.response.AdminPostResponse
import com.ninezero.core.common.util.PaginatedResponse
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

fun Route.postRoutes() {
    route(Constants.Endpoints.POSTS) {
        val postService by inject<PostService>()

        authenticate("jwt", optional = true) {
            get({
                summary = "포스트 목록 조회 (공개)"
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
                    queryParameter<String>("q") {
                        description = "검색 키워드"
                        required = false
                    }
                    queryParameter<String>("hashtag") {
                        description = "해시태그"
                        required = false
                    }
                    queryParameter<Int>("userId") {
                        description = "특정 사용자의 포스트 조회"
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
                apiResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "검색 조건 필요"
                    }
                }
            }) {
                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val query = call.getOptionalStringParam("q")
                val hashtag = call.getOptionalStringParam("hashtag")
                val userId = call.getOptionalIntParam("userId")
                val currentUserId = call.getUserIdOrNull()
                val sort = call.request.queryParameters["sort"]?.let {
                    runCatching { UserPostSortType.valueOf(it.uppercase()) }.getOrNull()
                } ?: UserPostSortType.LATEST
                val contextType = call.request.queryParameters["contextType"]?.let {
                    runCatching { PostContextType.valueOf(it.uppercase()) }.getOrNull()
                }

                val response = when {
                    query != null -> postService.searchPosts(query, currentUserId, page, limit)
                    hashtag != null -> postService.getPostsByHashtag(hashtag, currentUserId, page, limit)
                    userId != null -> postService.getUserPosts(userId, currentUserId, page, limit, sort, contextType)
                    else -> return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>(Errors.Social.Post.SEARCH_CONDITIONS_REQUIRED)
                    )
                }

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
                val tagId = call.getRequiredIntParam("tagId", Errors.Social.Tag.INVALID_TAG_ID)
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
                        body<ApiResponse<Any>>()
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

                    postService.updatePostWithMedia(
                        postId = postId,
                        userId = userId,
                        content = content,
                        keepMediaIds = if (keepMediaIds.isEmpty()) null else keepMediaIds,
                        newFiles = if (newFiles.isEmpty()) null else newFiles,
                        newContentTypes = if (newContentTypes.isEmpty()) null else newContentTypes,
                        reorder = if (reorder.isEmpty()) null else reorder
                    )
                } else {
                    val request = call.receive<UpdatePostRequest>()
                    postService.updatePost(postId, userId, request)
                }

                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Social.POST_UPDATED))
            }

            post("/{postId}/media", {
                summary = "포스트 미디어 업로드"
                tags("Posts")
                description = "이미지·비디오"
                request {
                    pathParameter<Int>("postId") {
                        description = "포스트 ID"
                        required = true
                    }
                    body<ByteArray> {
                        description = "Multipart form data - 파일 업로드 (타입은 파일별 content-type으로 자동 판별)"
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "미디어 업로드 성공"
                        body<ApiResponse<Any>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "파일이 없거나 잘못된 미디어 타입"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@post

                val (mediaFiles, contentTypes) = call.receiveFileParts()

                if (mediaFiles.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>(Errors.File.NO_MEDIA_FILES)
                    )
                }

                val response = postService.uploadPostMedia(postId, userId, mediaFiles, contentTypes)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Common.MEDIA_UPLOADED))
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
                        if (response["isPinned"] == true) Messages.Social.POST_PINNED else Messages.Social.POST_UNPINNED
                    )
                )
            }

            get("/my", {
                summary = "내 포스트 목록 조회"
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

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val sort = call.request.queryParameters["sort"]?.let {
                    runCatching { UserPostSortType.valueOf(it.uppercase()) }.getOrNull()
                } ?: UserPostSortType.LATEST
                val contextType = call.request.queryParameters["contextType"]?.let {
                    runCatching { PostContextType.valueOf(it.uppercase()) }.getOrNull()
                }

                val response = postService.getUserPosts(userId, userId, page, limit, sort, contextType)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{postId}/details", {
                summary = "포스트 상세 정보 (Admin)"
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
                        description = "관리자 권한 필요"
                    }
                }
            }) {
                call.requireAdminId() ?: return@get

                val postId = call.getRequiredIntParam("postId", Errors.Social.Post.INVALID_POST_ID)
                    ?: return@get

                val response = postService.getPostDetails(postId)
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
